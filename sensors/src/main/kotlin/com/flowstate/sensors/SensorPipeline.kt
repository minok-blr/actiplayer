package com.flowstate.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import com.flowstate.engine.FeatureFrame
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Turns raw phone sensors + the BLE heart-rate stream into 1 Hz [FeatureFrame]s (brief §6).
 *
 * Orientation-independent by construction: only vector magnitudes are used, because the
 * phone lives in an arbitrary pocket orientation. Everything here — sensor callbacks,
 * location callbacks, HR collection, and the 1 Hz ticker — runs on the main looper by
 * design, so the ring buffers need no synchronisation. All timestamps use elapsedRealtime
 * (monotonic), matching the clock the engine sees.
 */
class SensorPipeline(
    context: Context,
    private val scope: CoroutineScope,
    private val heartRate: HeartRateMonitor,
    private val maxHr: () -> Int,
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _frames = MutableStateFlow<FeatureFrame?>(null)
    val frames: StateFlow<FeatureFrame?> = _frames

    val hasBarometer: Boolean =
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    // Ring buffers of (elapsedRealtime ms, value).
    private val accelBuf = ArrayDeque<Pair<Long, Double>>()
    private val altBuf = ArrayDeque<Pair<Long, Double>>()
    private val hrBuf = ArrayDeque<Pair<Long, Int>>()
    private val gravity = FloatArray(3)
    private var gravityInitialised = false

    private var lastLocation: Location? = null
    private var tickerJob: Job? = null
    private var hrJob: Job? = null

    fun start() {
        stop()
        accelBuf.clear()
        altBuf.clear()
        hrBuf.clear()
        gravityInitialised = false
        lastLocation = null

        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        when {
            linear != null ->
                sensorManager.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
            accel != null ->
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        startLocationUpdates()

        hrJob = scope.launch {
            heartRate.readings.filterNotNull().collect { r ->
                // A StateFlow re-emits its current value on collect; skip exact duplicates.
                if (hrBuf.lastOrNull()?.first != r.tMillis) pushHr(r.tMillis, r.bpm)
            }
        }

        tickerJob = scope.launch {
            while (isActive) {
                emitFrame()
                delay(1_000)
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        hrJob?.cancel()
        hrJob = null
        sensorManager.unregisterListener(this)
        runCatching { locationManager.removeUpdates(locationListener) }
    }

    @SuppressLint("MissingPermission") // SessionCoordinator only starts a session when granted
    private fun startLocationUpdates() {
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener,
                )
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            if (!hasBarometer && location.hasAltitude()) {
                // GPS-altitude fallback for devices without a barometer; §6 compensates
                // for the extra noise with a much wider smoothing window.
                pushAltitude(SystemClock.elapsedRealtime(), location.altitude)
            }
        }

        // Explicit overrides of the legacy methods: on API 29 the framework interface has
        // no default implementations, and relying on them causes AbstractMethodError.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> pushAccel(now, magnitude(event.values))

            Sensor.TYPE_ACCELEROMETER -> {
                // Low-pass gravity estimate + subtraction, for devices without a fused
                // linear-acceleration sensor.
                val alpha = 0.8f
                if (!gravityInitialised) {
                    gravity[0] = event.values[0]
                    gravity[1] = event.values[1]
                    gravity[2] = event.values[2]
                    gravityInitialised = true
                } else {
                    for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i]
                }
                val lin = floatArrayOf(
                    event.values[0] - gravity[0],
                    event.values[1] - gravity[1],
                    event.values[2] - gravity[2],
                )
                pushAccel(now, magnitude(lin))
            }

            Sensor.TYPE_PRESSURE -> {
                val altitude = SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0],
                ).toDouble()
                // Absolute value is meaningless without sea-level calibration; only the
                // trend matters, and vertRate() only ever looks at differences.
                pushAltitude(now, altitude)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------------ features

    private fun emitFrame() {
        val now = SystemClock.elapsedRealtime()
        val bpm = latestHr(now)
        _frames.value = FeatureFrame(
            tMillis = now,
            motionRms = motionRms(now),
            vertRate = vertRate(now),
            speed = freshSpeed(now),
            hrBpm = bpm,
            hrPctMax = bpm?.let { it.toDouble() / maxHr().coerceAtLeast(1) },
            hrTrend = hrTrend(now),
        )
    }

    private fun motionRms(now: Long): Double? {
        val window = accelBuf.filter { now - it.first <= 3_000 }
        if (window.size < 10) return null
        val meanSq = window.sumOf { it.second * it.second } / window.size
        return sqrt(meanSq)
    }

    /**
     * Altitude slope: mean of the newest third of the window vs. the oldest third,
     * divided by their time separation. Cheap, robust to single-sample noise.
     */
    private fun vertRate(now: Long): Double? {
        val windowMs = if (hasBarometer) 12_000 else 25_000
        val minSpanMs = if (hasBarometer) 6_000 else 15_000
        val window = altBuf.filter { now - it.first <= windowMs }
        if (window.size < 4) return null
        val k = (window.size / 3).coerceAtLeast(1)
        val head = window.take(k)
        val tail = window.takeLast(k)
        val t0 = head.map { it.first }.average()
        val t1 = tail.map { it.first }.average()
        if (t1 - t0 < minSpanMs) return null
        val a0 = head.map { it.second }.average()
        val a1 = tail.map { it.second }.average()
        return (a1 - a0) / ((t1 - t0) / 1_000.0)
    }

    private fun freshSpeed(now: Long): Double? {
        val loc = lastLocation ?: return null
        val ageMs = now - loc.elapsedRealtimeNanos / 1_000_000
        if (ageMs > 5_000 || !loc.hasSpeed()) return null
        return loc.speed.toDouble()
    }

    /** Latest bpm if the monitor has reported within the last 10 s; otherwise null. */
    private fun latestHr(now: Long): Int? {
        val last = hrBuf.lastOrNull() ?: return null
        return if (now - last.first <= 10_000) last.second else null
    }

    /**
     * HR slope over the last ~60 s in bpm per minute — the "is the rider winding down"
     * signal (brief §6 hrTrend). Same head-third vs tail-third technique as vertRate.
     */
    private fun hrTrend(now: Long): Double? {
        val window = hrBuf.filter { now - it.first <= 60_000 }
        if (window.size < 6) return null
        val k = (window.size / 3).coerceAtLeast(1)
        val head = window.take(k)
        val tail = window.takeLast(k)
        val t0 = head.map { it.first }.average()
        val t1 = tail.map { it.first }.average()
        if (t1 - t0 < 20_000) return null
        val b0 = head.map { it.second }.average()
        val b1 = tail.map { it.second }.average()
        return (b1 - b0) / ((t1 - t0) / 60_000.0)
    }

    private fun magnitude(v: FloatArray): Double =
        sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())

    private fun pushAccel(t: Long, mag: Double) {
        accelBuf.addLast(t to mag)
        while (accelBuf.isNotEmpty() && t - accelBuf.first().first > 5_000) accelBuf.removeFirst()
    }

    private fun pushAltitude(t: Long, alt: Double) {
        altBuf.addLast(t to alt)
        while (altBuf.isNotEmpty() && t - altBuf.first().first > 40_000) altBuf.removeFirst()
    }

    private fun pushHr(t: Long, bpm: Int) {
        hrBuf.addLast(t to bpm)
        while (hrBuf.isNotEmpty() && t - hrBuf.first().first > 70_000) hrBuf.removeFirst()
    }
}
