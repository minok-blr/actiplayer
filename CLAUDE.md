# CLAUDE.md — ActiPlayer

## What this is
ActiPlayer (formerly FlowState) is an Android music player that adapts track energy to the
athlete's physical state. Sensors -> 1 Hz FeatureFrames -> deterministic per-activity
engine -> energy band 1-5 -> queue picks from the user's pools (local files or Spotify
playlists). Ski/snowboard first; cycling, running, strength, HIIT, generic follow.

## Source of truth, in priority order
1. `ACTIPLAYER_PLAN.md` — product decisions, architecture, screen map, build order (§6).
   Decisions in its §1 are final; do not re-open them.
2. `design/*.png` — the target screens, named by the IDs used in the plan (A1…D4).
   Match layout, spacing, type scale and colors. `design/ActiPlayer Redesign.dc.html`
   is the same design as HTML if you need exact values.
3. `DECISIONS.md` — every deliberate deviation, with rationale. Append; never contradict silently.
4. `README.md` — user-facing behavior and test scripts. Update as features land.

## Commands
- Build everything: `./gradlew build`
- Engine unit tests: `./gradlew :engine:test`
- Install debug APK: `./gradlew :app:installDebug`
- Gradle needs JDK 21 (see `~/.gradle/gradle.properties` → Android Studio JBR).

## Hard rules
- `:engine` never imports Android. Time arrives on frames; no wall-clock calls.
- All thresholds/windows live in `EngineConfig`. Nothing magic inline.
- Motion + elevation decide the mode; HR only modulates within it (cycling exception logged).
- Asymmetric hysteresis is sacred: up-switches fast (~3 s), down-switches sure (15-45 s).
- In-session touch targets ≥ 64 dp. Dark theme only. Mode color is the only saturated fill on session screens.
- Compile + tests green before claiming a phase done. No stubs presented as done.
- Product ambiguity -> ask the human. Technical ambiguity -> decide, log in DECISIONS.md.
- Verify library versions and platform rules against current docs, not memory.
- Secrets (Spotify client ID, creator unlock hash) live in `local.properties`, never in git.

## Working agreement
- One phase of `ACTIPLAYER_PLAN.md` §6 per session. At the end of a phase: build, test,
  install, then STOP and wait for on-device confirmation from the human.
- Update the phase tracker below and `DECISIONS.md` at every phase gate.

## Phase tracker (plan §6)
- [x] 1 Restructure (modules, Hilt, Room/DataStore, rename, 3-tab shell) — 2026-09-08
- [ ] 2 Music pools + local dual-player crossfade
- [ ] 3 Session UI (B1, B2/B3, ReasonFormatter, signal chips)
- [ ] 4 Recording + History + Recap + share + export
- [ ] 5 Wizard + permissions (A1–A5)
- [ ] 6 Profiles: Running, Generic, TimerEngine (D4, B4), sensitivity
- [ ] 7 Spotify (App Remote + Auth, C5, SourceRouter fallback)
- [ ] 8 Billing (Pro, trial, paywall D2, creator unlock)
- [ ] 9 Quick sort (C3) + tempo guess
- [ ] 10 Release pass

## Module map (after phase 1)
`:engine` (pure Kotlin, no Android) · `:data` (Room + DataStore + MediaStore) ·
`:sensors` (SensorPipeline, HeartRateMonitor) · `:playback` (ExoPlayer queue,
PlaybackService, Spotify remote) · `:session` (SessionCoordinator) · `:app` (Compose UI,
navigation). `:billing` is created in phase 8. DI is Hilt, `SingletonComponent` only.
Toolchain: Kotlin 2.3.21, AGP 8.13.2, compileSdk 36, targetSdk 35 — the AndroidX versions
in `gradle/libs.versions.toml` are capped by AGP 8.13 on purpose (see DECISIONS.md).

## Prior state (for context)
Phases 0-5 of the original FlowState brief were completed and verified on a Galaxy A52s
(2026-07-08): local playback, ratings, snowboard + cycling engines, BLE HR, Compose UI.
