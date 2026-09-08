# ActiPlayer

An Android music player that adapts what it plays to your **physical state**. Built for
skiing and snowboarding first: high-energy tracks while you ride, calm tracks the moment
you're on the chairlift, zero interaction required — the phone stays in your pocket, the
gloves stay on.

```
accelerometer + barometer + GPS + BLE heart rate
        ↓  (1 Hz FeatureFrames)
  ActivityEngine  →  RIDING / LIFT / BREAK  →  energy band 1–5 (HR-modulated within mode)
        ↓
  QueueController picks from your pools → ExoPlayer (or Spotify)
```

Product decisions, architecture and build order live in `ACTIPLAYER_PLAN.md`; every
deliberate deviation is logged in `DECISIONS.md`.

## Where the build is

Phase 1 of `ACTIPLAYER_PLAN.md` §6 (restructure) is done. Phases 2–10 are ahead.

**Working today**

- **Local music playback** via Media3/ExoPlayer with a MediaSession (lockscreen +
  notification controls, headphone-unplug pause, audio focus), in a typed foreground
  service so everything keeps working with the screen off.
- **Energy ratings** 1 (couch) → 5 (send it) on the Music tab, stored in Room.
  Unrated tracks count as 3.
- **The state engine** (`:engine`, pure Kotlin, 20 unit tests): deterministic rules with
  asymmetric hysteresis — switching *up* to hype confirms in ~3 s, switching *down* needs
  15–45 s of sustained evidence, so a quick stop never kills your song. Snowboard
  (RIDING/LIFT/PAUSED) and cycling (CRUISE/EFFORT) profiles.
- **Heart rate over standard BLE** (GATT Heart Rate Profile, 0x180D/0x2A37): Garmin in
  Broadcast Heart Rate mode, or any Polar/Wahoo/Coospo strap. HR **modulates energy
  within a mode, never the mode itself** (cycling is the logged exception). Losing the
  strap mid-session changes nothing about mode detection.
- **Manual override**: CHILL / AUTO / HYPE — the permanent trust valve.
- **Spotify** (Music → cloud icon): browse your playlists, rate tracks, and drive a
  session through Spotify Connect. Needs Premium; see `DECISIONS.md` for its limits.

**Not built yet** — History records nothing until phase 4; the Home and Music screens are
the pre-redesign layouts until phases 2–3; there is no onboarding wizard (phase 5),
Running/Strength/HIIT/Generic profiles (phase 6), or billing (phase 8).

## Project layout

| Module | Contents |
| --- | --- |
| `:engine` | Pure Kotlin state machines. No Android imports, no wall clock — time arrives on frames. |
| `:data` | Room (tracks, energies), DataStore settings, MediaStore scanning. |
| `:sensors` | `SensorPipeline` (accel RMS, vertical rate, speed) and `HeartRateMonitor`. |
| `:playback` | ExoPlayer queue, `PlaybackService`, Spotify remote. |
| `:session` | `SessionCoordinator` — wires sensors → engine → playback. |
| `:app` | Compose UI, navigation, DI entry point. |

DI is Hilt. Room schemas are exported to `data/schemas/` so every migration can be
written against a known previous version.

## Building it

Gradle needs **JDK 21** — `~/.gradle/gradle.properties` points `org.gradle.java.home` at
Android Studio's bundled JBR. The Android SDK needs platform 36 installed (`compileSdk`);
the app still targets 35.

```
./gradlew build              # everything, including lint
./gradlew :engine:test       # the state-machine suite
./gradlew :app:installDebug  # onto a connected physical device
```

Run on a **physical device** — the emulator has no useful sensors.

## Heart rate setup (Garmin)

1. On the watch, enable **Broadcast Heart Rate** — usually under Settings → Sensors &
   Accessories → Wrist Heart Rate → Broadcast Heart Rate (the exact path varies by model;
   some models only broadcast while an activity is running).
2. Phone Bluetooth ON. On Android 10–11, Location Services must also be ON — Android
   requires it for BLE scanning on those versions.
3. In ActiPlayer → Home → Heart rate card → **Set up monitor**, grant the Bluetooth
   permissions, and tap your watch when it appears. It is remembered from then on and
   reconnects automatically at every session start.

Watches that broadcast **ANT+ only** (mostly pre-~2019 Garmins) won't appear — the app
speaks standard Bluetooth GATT. Chest straps just work.

## Trying it indoors (no mountain required)

1. Put a few MP3s in the device's `Music/` folder, open ActiPlayer, grant permissions.
2. In **Music**, rate a couple of tracks 1–2 and a couple 4–5.
3. On **Home**, hit **Start session**. A mid-energy track starts.
4. **Shake the phone hard for ~4 seconds** → the blind-riding rule fires, mode flips to
   RIDING, and a 4–5 rated track cuts in fast.
5. **Set the phone down for ~30 seconds** → PAUSED confirms, music eases back to mid.
6. LIFT needs a real climb signal (`vertRate`): on a phone with a barometer, ride an
   elevator or walk stairs slowly and steadily and watch `vertRate` go positive.
7. **Heart rate**: connect your watch (above) and watch live bpm on the HR card. To see
   modulation without redlining, lower **Max HR** with the −5 button until your current
   bpm exceeds 85% of it, then shake into RIDING: the band narrows to 5–5 and the reason
   reads "HR at NN% of max".
8. The CHILL/HYPE chips override the engine at any time; AUTO hands control back.

Upgrading from a FlowState build? Your existing ratings are imported into Room on first
launch; the old preference files are left in place untouched.
