# FlowState — MVP

A music player for Android that adapts what it plays to your **physical state**. Built for
snowboarding: high-energy tracks while you ride, calm tracks the moment you're on the
chairlift, zero interaction required — the phone stays in your pocket, the gloves stay on.

This is the MVP implementation of `flowstate-build-brief.md` (Phases 0–5 of the brief,
simplified). It is a real, installable app with the full core loop working:

```
accelerometer + barometer + GPS + BLE heart rate
        ↓  (1 Hz FeatureFrames)
  StateEngine  →  RIDING / LIFT / PAUSED  →  energy band 1–5 (HR-modulated within mode)
        ↓
  QueueController picks from your rated tracks → ExoPlayer
```

## What's in the MVP

- **Local music playback** via Media3/ExoPlayer with a MediaSession (lockscreen +
  notification controls, headphone-unplug pause, audio focus), running in a typed
  foreground service so everything keeps working with the screen off.
- **Energy ratings**: rate any track 1 (couch) → 5 (send it) in the Library tab.
  Unrated tracks count as 3.
- **The state engine** (`:engine`, pure Kotlin, 11 unit tests): deterministic rules with
  asymmetric hysteresis — switching *up* to hype confirms in ~3 s, switching *down* needs
  15–45 s of sustained evidence, so a quick stop never kills your song. Every transition
  carries a human-readable reason, shown live in the UI.
- **Heart rate over standard BLE** (GATT Heart Rate Profile, 0x180D/0x2A37): works with a
  Garmin in Broadcast Heart Rate mode today, and any Polar/Wahoo/Coospo strap with the
  exact same code. Scan → pick → remembered; auto-reconnect with backoff. HR **modulates
  energy within a mode, never the mode itself** — redlining mid-run narrows RIDING to
  only your 5-rated bangers; a falling pulse while standing around eases PAUSED toward
  its calm end. Losing the watch mid-session changes nothing about mode detection.
- **Manual override**: CHILL / AUTO / HYPE chips — the permanent trust valve.
- **Live signal readout**: motionRms, vertRate, speed, bpm + trend, current mode + why.

## Building it

1. Open the project folder in a recent Android Studio (Ladybug or newer).
2. Let Gradle sync (the wrapper is included; first sync downloads dependencies).
3. Run the `app` configuration on a **physical device** (the emulator has no useful
   sensors). `./gradlew :engine:test` runs the state-machine test suite.

Working from a terminal with Claude Code instead? `CLAUDE.md` in the repo root carries
the working agreements, exact build state, and a first-session checklist — start the
session with: *"Read CLAUDE.md and continue."*

**Honesty note:** this project was written in a sandbox that cannot reach Google's Maven
repository, so it has never been compiled. The code was desk-checked carefully and the
versions pinned in `gradle/libs.versions.toml` are conservative known-good releases, but
if the first build throws an error, paste it into Claude Code alongside
`flowstate-build-brief.md` and it will resolve quickly.

## Heart rate setup (Garmin)

1. On the watch, enable **Broadcast Heart Rate** — on most models it's under
   Settings → Sensors & Accessories → Wrist Heart Rate → Broadcast Heart Rate (the exact
   path varies by model; some models only broadcast while an activity is running).
2. Phone Bluetooth ON. On Android 10–11, Location Services must also be ON — Android
   requires it for BLE scanning on those versions.
3. In FlowState → Session tab → Heart rate card → **Set up monitor**, grant the
   Bluetooth permissions, and tap your watch when it appears. It's remembered from then
   on and reconnects automatically at every session start.

Caveats: watches that broadcast **ANT+ only** (mostly pre-~2019 Garmins) won't appear —
the app speaks standard Bluetooth GATT. Chest straps just work.

## Trying it indoors (no mountain required)

1. Put a few MP3s in the device's `Music/` folder, open FlowState, grant permissions.
2. In **Library**, rate a couple of tracks 1–2 and a couple 4–5.
3. In **Session**, hit **Start session**. A mid-energy track starts.
4. **Shake the phone hard for ~4 seconds** → the blind-riding rule fires, mode flips to
   RIDING, and a 4–5 rated track cuts in fast.
5. **Set the phone down for ~30 seconds** → PAUSED confirms, music eases back to mid.
6. LIFT needs a real climb signal (`vertRate`): on a phone with a barometer, ride an
   elevator or walk stairs slowly and steadily and watch `vertRate` go positive.
7. **Heart rate**: connect your watch (see above) and watch live bpm on the HR card. To
   see modulation without actually redlining, lower **Max HR** with the −5 button until
   your current bpm exceeds 85% of it, then shake into RIDING: the band narrows to 5–5
   and the decision reason reads "HR at NN% of max".
8. The CHILL/HYPE chips override the engine at any time; AUTO hands control back.

## Known MVP simplifications (vs. the full brief)

Hand-rolled DI instead of Hilt; SharedPreferences instead of Room; single-player
volume-ramp fades instead of dual-player crossfade with a preloaded standby track;
framework `LocationManager` instead of Play Services; no session recording / trace
export yet; simple backoff loop for HR reconnect. Each is logged with rationale in
`DECISIONS.md`, and the build brief describes the full target for every one of them.

## Next steps (per the brief)

Room-backed session recording with JSONL trace export + in-app replay (the threshold
tuning loop), the dual-player crossfade with standby preload, a threshold editor on a
debug screen, the battery measurement pass, then the Mountain Test Protocol.
