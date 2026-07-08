# CLAUDE.md — FlowState

## What this is
FlowState is an Android music player that adapts track energy to the athlete's physical
state (snowboarding first): sensors -> 1 Hz FeatureFrames -> deterministic StateEngine
(RIDING / LIFT / PAUSED) -> energy band 1-5 -> queue picks from user-rated local tracks.
BLE heart rate (standard GATT HR profile; a Garmin in Broadcast HR mode) modulates energy
within a mode, never the mode itself.

## Source of truth, in priority order
1. `flowstate-build-brief.md` — the full product + architecture brief. If it isn't in the
   repo root, ask the human for it before making product-level decisions.
2. `DECISIONS.md` — every deliberate deviation from the brief, with rationale. Append to
   it; never silently contradict it.
3. `README.md` — user-facing behavior, setup, and the human test scripts.

## Commands
- Build everything: `./gradlew build`
- Engine unit tests only: `./gradlew :engine:test`
- Install debug APK on a connected device: `./gradlew :app:installDebug`
- Gradle needs JDK 21: the system Java is 25 (unsupported by Gradle 8.13). This machine's
  `~/.gradle/gradle.properties` points `org.gradle.java.home` at Android Studio's bundled
  JBR (`/usr/local/android-studio/jbr`), so plain `./gradlew` works.

## Hard rules (from brief §16)
- `:engine` never imports Android. Time is injected via frames; no wall-clock calls.
- All thresholds/windows live in `EngineConfig` — nothing magic inline.
- Motion + elevation decide the mode; HR only modulates within it (HR lags 1-3 min).
  Exception: the cycling profile lets HR corroborate the up-switch (see DECISIONS.md).
- Asymmetric hysteresis is sacred: up-switches fast (~3 s), down-switches sure (15-45 s).
- Compile + tests green before claiming any step done. No stub code presented as done.
- Product ambiguity -> ask the human. Technical ambiguity -> decide, log in DECISIONS.md.
- Verify library versions and platform rules against current docs, not memory.

## Current state
- Phases 0-4 of the brief (MVP-simplified) plus Phase 5 (BLE heart rate) are implemented.
- First compile done 2026-07-08: `./gradlew build` succeeded with **zero source fixes**
  (debug + release APKs, lint clean). On-device verification 2026-07-08 on a Galaxy A52s:
  app runs, BLE HR pairing works (Garmin Instinct E), playback + mode switching confirmed
  by the human.
- UI reworked 2026-07-08 (see DECISIONS.md): first-launch full-screen guide + "?" help
  button, idle "launch checklist" vs. active "Ride Board" session screens, dark-only
  token theme (`ui/Theme.kt`), library filter chips + compact rating rows, HR setup
  prompts to enable Bluetooth itself.
- Activity profiles added 2026-07-08: `Activity` enum + `ActivityEngine` interface;
  cycling implemented as `CyclingEngine` (CRUISE/EFFORT, HR co-decides the up-switch —
  logged deviation) selected per session from the Session tab; running/hiking are
  disabled menu placeholders. 20 `:engine` tests (11 snowboard + 9 cycling).
- Implemented:
  - MediaStore library + 1-5 energy ratings (SharedPreferences).
  - Media3/ExoPlayer playback in a typed FGS (`mediaPlayback|location`) with MediaSession.
  - `SensorPipeline`: linear accel (low-pass fallback), barometer with GPS-altitude
    fallback, framework LocationManager GPS, and an HR buffer producing
    hrBpm / hrPctMax / hrTrend.
  - `StateEngine`: confirmation windows, min dwell, decay, RIDING priority, and HR
    modulation via `energyFor(state, frame)` + `settle()`. 11 JUnit tests, desk-checked.
  - `QueueController`: band selection with ±1 widening, no-repeat memory, asymmetric
    volume-ramp fades (fast up, gentle down).
  - `HeartRateMonitor`: GATT 0x180D/0x2A37 client, scan filtered on the HR service,
    remembered device, backoff auto-reconnect, dual API-level characteristic callbacks,
    flags-byte parsing (uint8/uint16).
  - Compose UI: Session + Library tabs, CHILL/AUTO/HYPE chips, HR setup card with max-HR
    steppers, live signal readout with decision reasons.
- Not implemented yet (deliberate; see DECISIONS.md): Hilt, Room, session recording /
  JSONL trace export + replay, dual-player crossfade with standby preload, gyro/heading
  features, threshold editor UI, battery pass.

## First session checklist
1. ~~`./gradlew build`~~ DONE 2026-07-08 — compiled clean, no fixes needed.
2. ~~`./gradlew :engine:test`~~ DONE 2026-07-08 — 11/11 pass.
3. Ask the human to install on a physical device and run the README indoor test script
   (shake -> HYPE; still 30 s -> PAUSED; Garmin broadcast -> live bpm; lower Max HR to
   force HR modulation and confirm the decision reason reads "HR at NN% of max").
4. Then continue with the remaining brief items, one phase gate at a time, updating the
   tracker below and DECISIONS.md as you go.

## Phase tracker
- [x] P0 scaffold   [x] P1 real player   [x] P2 ratings + manual modes
- [x] P3 sensor pipeline   [x] P4 engine loop   [x] P5 heart rate
  (compile + unit tests green 2026-07-08; on-device verification still pending)
- [ ] P6 polish + battery pass
- [ ] Session recording (Room) + JSONL trace export + in-app replay
- [ ] Dual-player crossfade with preloaded standby track
- [ ] Threshold editor / debug dashboard extensions
