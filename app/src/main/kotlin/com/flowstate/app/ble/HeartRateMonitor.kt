package com.flowstate.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class HrReading(val tMillis: Long, val bpm: Int)

data class HrDevice(val address: String, val name: String?)

sealed interface HrState {
    data class Disconnected(val rememberedName: String?) : HrState
    data object Scanning : HrState
    data class Connecting(val name: String?) : HrState
    data class Connected(val name: String?) : HrState
    data class Reconnecting(val name: String?) : HrState
}

/**
 * BLE heart-rate client implementing the standard GATT Heart Rate Profile (build brief
 * §11): Heart Rate service 0x180D, Heart Rate Measurement characteristic 0x2A37,
 * notifications, flags byte parsed for uint8 vs uint16 bpm.
 *
 * This one implementation covers a Garmin watch in Broadcast Heart Rate mode AND
 * Polar / Wahoo / Coospo chest straps — which is exactly the roadmap. No vendor SDKs.
 *
 * Lifecycle: scan (filtered on the HR service UUID) -> user picks a device -> address is
 * remembered -> connect. On unexpected disconnect while a connection is desired, a
 * backoff loop retries until told to stop. Loss of HR never affects mode logic — the
 * engine treats null HR features as "absent" by design (brief §2.3).
 *
 * Threading: GATT and scan callbacks arrive on binder threads. StateFlow writes are
 * thread-safe; anything touching the gatt/reconnect fields is posted to the main scope.
 */
@SuppressLint("MissingPermission") // every entry point is gated on granted BT permissions in the UI
class HeartRateMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hr", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter // null on hardware without Bluetooth

    private val _state = MutableStateFlow<HrState>(HrState.Disconnected(rememberedName()))
    val state: StateFlow<HrState> = _state

    /** Latest reading; null when disconnected. Consumed by SensorPipeline. */
    private val _readings = MutableStateFlow<HrReading?>(null)
    val readings: StateFlow<HrReading?> = _readings

    private val _scanResults = MutableStateFlow<List<HrDevice>>(emptyList())
    val scanResults: StateFlow<List<HrDevice>> = _scanResults

    private var gatt: BluetoothGatt? = null
    private var desired = false // should we hold / re-establish a connection?
    private var reconnectJob: Job? = null
    private var scanStopJob: Job? = null
    private var scanning = false

    fun rememberedName(): String? = prefs.getString(KEY_NAME, null)
    private fun rememberedAddress(): String? = prefs.getString(KEY_ADDR, null)

    // ------------------------------------------------------------------ scanning

    fun startScan() {
        val a = adapter ?: return
        if (!a.isEnabled || scanning) return
        val scanner = a.bluetoothLeScanner ?: return
        _scanResults.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        _state.value = HrState.Scanning
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanStopJob = scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanStopJob?.cancel()
        scanStopJob = null
        if (scanning) {
            scanning = false
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        if (_state.value is HrState.Scanning) {
            _state.value = HrState.Disconnected(rememberedName())
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val entry = HrDevice(result.device.address, result.device.name)
            scope.launch {
                val current = _scanResults.value
                val existing = current.firstOrNull { it.address == entry.address }
                _scanResults.value = when {
                    existing == null -> current + entry
                    existing.name == null && entry.name != null ->
                        current.map { if (it.address == entry.address) entry else it }
                    else -> current
                }
            }
        }
    }

    // ------------------------------------------------------------------ connecting

    /** Connect to the remembered monitor, if any. Safe no-op when none is set up. */
    fun connect() {
        val address = rememberedAddress() ?: return
        establish(address, rememberedName())
    }

    fun connectTo(device: HrDevice) {
        stopScan()
        prefs.edit().putString(KEY_ADDR, device.address).putString(KEY_NAME, device.name).apply()
        establish(device.address, device.name)
    }

    fun disconnect() {
        desired = false
        reconnectJob?.cancel()
        reconnectJob = null
        teardownGatt()
        _readings.value = null
        _state.value = HrState.Disconnected(rememberedName())
    }

    fun forget() {
        disconnect()
        prefs.edit().clear().apply()
        _state.value = HrState.Disconnected(null)
    }

    private fun establish(address: String, name: String?) {
        val a = adapter ?: return
        if (!a.isEnabled) return
        desired = true
        reconnectJob?.cancel()
        reconnectJob = null
        teardownGatt()
        _state.value = HrState.Connecting(name)
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            _state.value = HrState.Disconnected(rememberedName())
            return
        }
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun teardownGatt() {
        runCatching { gatt?.close() }
        gatt = null
    }

    /** Backoff retry loop; runs until connected, cancelled, or no longer desired. */
    private fun scheduleReconnect() {
        val address = rememberedAddress() ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive && desired && _state.value !is HrState.Connected) {
                attempt++
                _state.value = HrState.Reconnecting(rememberedName())
                delay((attempt * 2_000L).coerceAtMost(15_000L))
                if (!desired) break
                teardownGatt()
                _state.value = HrState.Connecting(rememberedName())
                val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: break
                gatt = device.connectGatt(
                    appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
                )
                delay(12_000L) // give this attempt time to land before retrying
            }
        }
    }

    // ------------------------------------------------------------------ GATT plumbing

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    // Not "connected" for our purposes until notifications flow;
                    // service discovery continues the chain.
                    gatt.discoverServices()

                BluetoothProfile.STATE_DISCONNECTED -> scope.launch {
                    teardownGatt()
                    _readings.value = null
                    when {
                        !desired -> _state.value = HrState.Disconnected(rememberedName())
                        reconnectJob?.isActive == true -> Unit // retry loop is already driving
                        else -> scheduleReconnect()
                    }
                }
            }
        }

        @Suppress("DEPRECATION") // legacy descriptor-write path works uniformly on API 29–35
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.disconnect()
                return
            }
            val characteristic = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (characteristic == null) {
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val ccc = characteristic.getDescriptor(CCC_DESCRIPTOR) ?: return
            ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(ccc)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCC_DESCRIPTOR) {
                scope.launch {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    _state.value = HrState.Connected(rememberedName())
                }
            }
        }

        // API < 33 callback.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            handleValue(characteristic.uuid, value)
        }

        // API 33+ callback.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleValue(characteristic.uuid, value)
        }
    }

    private fun handleValue(uuid: UUID, value: ByteArray) {
        if (uuid != HR_MEASUREMENT) return
        val bpm = parseHeartRate(value) ?: return
        _readings.value = HrReading(SystemClock.elapsedRealtime(), bpm)
    }

    /**
     * GATT Heart Rate Measurement (0x2A37): flags byte bit 0 selects uint8 vs uint16 LE bpm.
     */
    private fun parseHeartRate(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt()
        return if (flags and 0x01 != 0) {
            if (value.size < 3) null
            else (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else {
            if (value.size < 2) null
            else value[1].toInt() and 0xFF
        }
    }

    private companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val KEY_ADDR = "device_address"
        const val KEY_NAME = "device_name"
    }
}
