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
  LocalSource picks from your pools → dual-player crossfade (or Spotify)
```

Product decisions, architecture and build order live in `ACTIPLAYER_PLAN.md`; every
deliberate deviation is logged in `DECISIONS.md`.

## Where the build is

Phases 1–3, 5 and 6 are done, and phase 4 in reduced form (recording, History, Recap;
the share card and JSON export are deferred to the release pass). Phases 7–10 are ahead.

**Working today**

- **Local music playback** via Media3/ExoPlayer with a MediaSession, in a foreground
  service (media playback + location) so sensors, GPS and music keep running with the
  screen off and the phone in a pocket. The notification shows the mode the track was
  picked for ("Architects · RIDING"); lockscreen, notification and headset buttons
  pause, resume and skip. A pause is yours: it holds through mode changes — the right
  track is cued silently — until you resume, whether it came from the screen, the
  notification, headphones unplugging or another app taking the audio.
- **Energy pools**: three levels — Hype / Mid / Chill — filled from folders, albums or
  device playlists on the Music tab. The engine only ever picks from these. A track's
  energy resolves as *excluded → your explicit rating → playlist → album → folder*;
  anything neither rated nor pooled is "unsorted" and never plays. Podcasts are dropped
  outright — no folder, pool or rating can put an episode into a session. With no pools
  set up at all, sessions fall back to the whole library (minus podcasts) at mid energy.
- **Dual-player crossfade**: two ExoPlayers alternate, with the next track preloaded on
  the idle one, so an up-switch cuts in fast (300 ms) while a down-switch eases over
  1–3 s. Track ends overlap too: the next track starts a crossfade before the current
  one finishes. A small pool repeats its own tracks rather than leaving the band — a
  riding block never gets a chill song because the Hype pool ran out.
- **The state engines** (`:engine`, pure Kotlin, 62 unit tests): deterministic rules with
  asymmetric hysteresis — switching *up* to hype confirms in ~3 s, switching *down* needs
  15–45 s of sustained evidence, so a quick stop never kills your song. Snowboard
  (RIDING/LIFT/BREAK), cycling (CRUISE/EFFORT), walking (STROLL/BRISK), running
  (WALK/CRUISE/PUSH) and a motion-only generic profile (REST/EASY/ACTIVE). Strength and
  HIIT run on a **work/rest clock** instead — Tabata, EMOM, 30/30, 30/90 or custom — since
  indoors there is no GPS or altitude to read.
- **Switch sensitivity** (Quick / Balanced / Sure) scales how patient the engine is about
  calming down. It never touches how fast it fires up.
- **Heart rate over standard BLE** (GATT Heart Rate Profile, 0x180D/0x2A37): Garmin in
  Broadcast Heart Rate mode, or any Polar/Wahoo/Coospo strap. HR fine-tunes the energy
  within a mode; on a bike, a walk or a run it also helps confirm an effort (logged
  exceptions — on a bike the pulse may lag the speed gain by up to 45 s and still count).
  Losing the strap mid-session changes nothing about mode detection.
- **The session screen**: full-bleed mode colour, the mode name at 72 sp, and a plain
  sentence saying why — "Descending fast with heavy motion for 3 s." Four signal chips
  (MOTION / ALT / SPEED / HR) light up to show *which* signals decided it, and a
  five-segment meter shows the energy band. Screen stays awake only while charging.
- **Manual override**: CHILL / AUTO / HYPE — the permanent trust valve. Pause and skip
  sit beside the track, 56 dp, with a haptic tick on every in-session tap.
- **Session recording and recap**: every session is saved and shows up in History with a
  mode strip. Opening one gives the mode timeline with heart rate and speed drawn over it,
  time per mode, top tracks per mode, and an **override count** — every override is the
  engine being corrected by hand, so that number is the honest score for the thresholds
  in `EngineConfig`. Sessions can be deleted from their recap.
- **Settings** (gear on Home): max heart rate, monitor, units (km/h or mph), music source,
  crossfade, pause-on-unplug, switch sensitivity, live signals, and setup again.
- **Spotify** (Music → cloud icon): browse your playlists, rate tracks, and drive a
  session through Spotify Connect. Needs Premium; see `DECISIONS.md` for its limits.

**Not built yet** — no billing or Pro gating (phase 8), and Spotify still runs on the
older Web-API path until phase 7. Quick sort (C3)
lands in phase 9 — until then the "Sort" button opens the bulk editor.

## Project layout

| Module | Contents |
| --- | --- |
| `:engine` | Pure Kotlin state machines. No Android imports, no wall clock — time arrives on frames. |
| `:data` | Room (tracks, energies, pools), DataStore settings, MediaStore scanning, energy resolution. |
| `:sensors` | `SensorPipeline` (accel RMS, vertical rate, speed) and `HeartRateMonitor`. |
| `:playback` | Dual-player `LocalSource`, track selection, `PlaybackService`, Spotify remote. |
| `:session` | `SessionCoordinator` — wires sensors → engine → playback. |
| `:app` | Compose UI, navigation, DI entry point. |

DI is Hilt. Room schemas are exported to `data/schemas/` so every migration can be
written against a known previous version.

Cover art comes from `ContentResolver.loadThumbnail` on the track URI — the
`ALBUM_ART` MediaStore column is dead on modern Android. Tracks with no embedded art
fall back to a generated tile in their energy colour, never an empty square.

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
3. In ActiPlayer → Home → the heart-rate row → **Find a monitor**, grant the Bluetooth
   permissions (and tap **Turn on Bluetooth** if it is off), then tap your watch when it
   appears. It is remembered from then on and reconnects automatically at every session
   start.

Watches that broadcast **ANT+ only** (mostly pre-~2019 Garmins) won't appear — the app
speaks standard Bluetooth GATT. Chest straps just work.

## Trying it indoors (no mountain required)

1. Put a few MP3s in the device's `Music/` folder and open ActiPlayer. The setup
   wizard asks for each permission one at a time, explaining what it is for; the
   heart-rate step is skippable. Re-run it later from Home → Settings → Run setup again.
2. In **Music**, tap **+ Add** and put one folder into **Hype** and another into
   **Chill**. Both pools want 8+ tracks or you will hear repeats.
3. On **Home**, hit **Start session**. A mid-energy track starts.
4. **Shake the phone hard for ~4 seconds** → the blind-riding rule fires, mode flips to
   RIDING, and a 4–5 rated track cuts in fast.
5. **Set the phone down for ~30 seconds** → PAUSED confirms, music eases back to mid.
6. LIFT needs a real climb signal (`vertRate`): on a phone with a barometer, ride an
   elevator or walk stairs slowly and steadily and watch `vertRate` go positive.
7. **Heart rate**: connect your watch (above) and watch live bpm on the Home row. To see
   modulation without redlining, lower **Max HR** (Settings, hold − to repeat) until your
   current bpm exceeds 85% of it, then shake into RIDING: the band narrows to 5–5 and the
   reason reads "Pulse at NN% of max".
8. The CHILL/HYPE chips override the engine at any time; AUTO hands control back.

**Testing on foot instead:** set Activity to **Walking**. Striding out — over ~5.4 km/h,
or just a clear walking cadence with the phone in a pocket — flips to BRISK once your
pulse is past ~78 bpm (movement alone is enough with no strap paired). Ambling or standing
still drops back to STROLL after ~20 s. It works indoors with no GPS fix at all, so a
corridor is a valid test. Thresholds live in `EngineConfig` as `walking*`.

**Testing in a pocket:** the session keeps running with the screen off — that is what
the foreground service is for — so a real walk, ride or run is the test that counts.
Afterwards the recap's timeline and override count say how well the switches landed.
Running reads your stride from the accelerometer as well as GPS; if it ever shows PUSH
while you are only walking, that is worth reporting (see `DECISIONS.md`, 2026-09-29).

Upgrading from a FlowState build? Your existing ratings are imported into Room on first
launch; the old preference files are left in place untouched.
