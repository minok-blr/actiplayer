# ActiPlayer — Architecture & Design Plan (v1, store-ready)

Feed this to the implementation model together with the repo (`minok-blr/actiplayer`, branch `main`) and the two design files: `Current UI.dc.html` (what exists) and `ActiPlayer Redesign.dc.html` (target screens A1–D4).

## 0. Product in one paragraph

ActiPlayer is an Android music player that adapts the energy of what plays to the athlete's physical state, hands-free. Built for skiing/snowboarding first (RIDING / LIFT / BREAK), with profiles for cycling, running, strength, HIIT and a generic motion-only mode. Music comes from local files (Free) or Spotify playlists (Pro). Every in-session decision is explainable on screen. Phone stays in the pocket; every target is glove-sized.

## 1. Decisions already made (do not re-open)

| Topic | Decision |
| --- | --- |
| Platform | Android only, Kotlin + Jetpack Compose, evolve the existing repo. Min SDK 29, target 35. |
| Name | **ActiPlayer** (rename from FlowState: `app_name`, package stays `com.flowstate.*` unless trivial). |
| Theme | Dark only, keep exact palette from `ui/Theme.kt`. |
| Navigation | 3 bottom tabs: Home · Music · History. Settings via avatar on Home. |
| Onboarding | Full wizard: Welcome → Activity → Music source → Heart rate (optional, incl. Max HR) → Permissions (per-signal, in-context). |
| Sources | Local files (MediaStore) + Spotify (App Remote SDK). Others out of scope. |
| Spotify model | One playlist per energy level (Hype / Mid / Chill), optional per-track 1–5 rating of imported playlists. |
| Spotify fallback | Automatically fall back to local pools when Spotify cannot play; return when it can. |
| Activities at launch | Ski/Snowboard, Cycling, Running, Strength, HIIT, Generic. HR optional everywhere. |
| Strength / HIIT | Timer-driven (presets Tabata, EMOM, 30/30, Strength 30/90 + custom). Work = Hype, Rest = Chill. |
| Generic profile | 3 states: ACTIVE / EASY / REST (motion RMS only, HR modulates). |
| Transitions | Crossfade 1–3 s (dual player). Up-switch still cuts fast (≤ 400 ms). |
| Session data | Rich recap: mode timeline vs HR/speed, top tracks, stats, image share card. History = list → recap. Export JSON. |
| Monetization | Free + Pro, 7-day free trial then paywall (Play Billing). No ads. |
| Pro | Spotify, activities beyond Ski + Generic, AI section-seek (v2). Free keeps ski, generic, local music, history. |
| Units | Metric default (setting exists). |
| Notification | Standard media controls only (Media3 MediaSession). Mode shown as subtitle text. |
| In-session "why" | Plain sentence + signal chips (MOTION / ALT / SPEED / HR) that light up when they contributed. |
| Wearable | None. BLE HR from any GATT strap/watch. |
| AI energetic-section seek | Design placeholder only; ship in v2. |

## 2. What to keep from the repo

- `:engine` module (pure Kotlin, no Android): `FeatureFrame`, `EngineConfig`, `StateEngine` (snowboard), `CyclingEngine`, `ActivityEngine` interface, `RideState`, `StateDecision`. All 20 tests. Invariants from `CLAUDE.md` §Hard rules stay.
- `SensorPipeline` (accel RMS, barometer/GPS vertRate, GPS speed, HR buffer → 1 Hz frames).
- `HeartRateMonitor` (GATT 0x180D/0x2A37, remembered device, backoff reconnect).
- `PlaybackService` (Media3 MediaSessionService, FGS `mediaPlayback|location`).
- Asymmetric hysteresis, decision `reason` strings, CHILL/AUTO/HYPE override, HR-modulates-within-mode.
- Palette: ground `#0B0E14`, surface `#151B26`, surfaceHigh `#1B2230`, snow `#F2F5F9`, dim `#8B96A8`, outline `#232C3C`, riding `#FF5A1F`, lift `#4FC3F7`, paused `#7E8AA0`, ok `#6FDB8F`, hot `#FFB59D`. Spotify green `#1DB954` only for the Spotify mark.

## 3. What changes

### 3.1 Data layer
- Replace SharedPreferences for ratings with **Room**. Keep `SettingsStore` on DataStore (Preferences).
- Entities: `Track` (mediaStoreId | spotifyUri, title, artist, durationMs, source, folder, album, bpmGuess?), `TrackEnergy` (trackId, band 1–5 or `EXCLUDED`, source: USER | GUESS | POOL), `Pool` (energyLevel HYPE/MID/CHILL → list of `PoolSource` = folder | album | device playlist | spotifyPlaylistUri), `Session`, `SessionEvent` (t, kind: MODE/TRACK/OVERRIDE/HR/SPEED sample), `TimerPreset`.
- Energy resolution order for a local track: user rating → pool membership (HYPE=5, MID=3, CHILL=1 with ±1 widening allowed) → tempo guess → 3.
- Rename bands for users: **Chill = 1–2, Mid = 3, Hype = 4–5**. Engine keeps IntRange 1..5.

### 3.2 Music sources (`:playback`)
- `MusicSource` interface: `prepare(pool)`, `play(energyBand, fast: Boolean)`, `skip()`, `pause()`, `resume()`, `stop()`, `nowPlaying: Flow<NowPlaying?>`, `availability: Flow<Availability>`.
- `LocalSource`: two ExoPlayers (A/B) for real crossfade; standby track preloaded for the *other* band so an up-switch is instant. Crossfade duration from settings (1–3 s); up-switch uses 300 ms.
- `SpotifySource`: Spotify App Remote SDK (`SpotifyAppRemote.connect`), `playerApi.play(playlistUri)`, `setShuffle(true)`, `skipNext()`. Requires Spotify app installed + Premium. Poll `playerState` for now-playing. Auth via Spotify Auth library (PKCE) for Web API playlist listing (`GET /me/playlists`). Note: Spotify Audio Features API is deprecated for new apps; do not depend on it for energy guessing.
- `SourceRouter`: prefers Spotify when a playlist is mapped for the requested band and `Availability == READY`; otherwise routes to `LocalSource`. Emits `FallbackEvent` shown as a small line in the now-playing card ("Spotify unavailable · playing local").

### 3.3 Activity profiles (`:engine`)
| Activity | States | Primary signals | Band mapping |
| --- | --- | --- | --- |
| Ski/Snowboard | RIDING / LIFT / BREAK (rename PAUSED→BREAK in UI only) | motionRms, vertRate, speed | 4–5 / 1–2 / 2–3 |
| Cycling | EFFORT / CRUISE | speed delta, HR trend (co-decides) | 4–5 / 1–2 |
| Running | PUSH / CRUISE / WALK | speed, cadence (accel peaks/min), HR% | 4–5 / 3 / 1–2 |
| Strength | SET / REST | timer; motion burst can end SET early (opt-in) | 4–5 / 1–2 |
| HIIT | WORK / REST | timer | 5 / 1–2 |
| Generic | ACTIVE / EASY / REST | motionRms bands, HR% modulates | 4–5 / 3 / 1–2 |

All thresholds in `EngineConfig` (add `running*`, `generic*`). `TimerEngine` implements `ActivityEngine` and takes a `TimerPlan`. New "Switch sensitivity" setting (Quick / Balanced / Sure) scales all *down*-switch confirm windows by 0.6 / 1.0 / 1.5. Up-switch windows never scale.

### 3.4 Session recording (`:session`)
- `SessionRecorder` writes `SessionEvent`s at 1 Hz (downsample HR/speed to 5 s for storage) + every mode/track/override change. Recap computes: time per mode, avg/max HR, top speed, switch count, top tracks per mode.
- Share card: render a Compose `Picture` at 1080×1350, write PNG to cache, share via `Intent.ACTION_SEND`. Layout per screen B6.
- Export: JSON per session (events + config snapshot) via `ACTION_CREATE_DOCUMENT`.

### 3.5 Billing
- Play Billing Library 7. Products: `pro_yearly` (€19.99), `pro_monthly` (€2.99), both with 7-day free trial offer. `Entitlements` flow → `isPro`.
- **Creator / lifetime Pro**: `isPro = billingEntitled || creatorUnlocked`. `creatorUnlocked` is set by entering an unlock code in Settings → Account → tap the version number 7 times → "Unlock code" field. The code is verified as `SHA-256(code + "actiplayer-salt")` against a constant compiled into the app (never store the plain code in the repo; put it in `local.properties` as `CREATOR_CODE_HASH` and read via `BuildConfig`). Stored in encrypted DataStore; survives reinstall only if backed up via Auto Backup. Also honour Play Console **license testers** (the creator's Google account) so test purchases are free during development. Gate: activity picker cards (lock badge on Pro activities), Spotify source, section-seek toggle. Paywall sheet = screen D2. Day-5 reminder via WorkManager notification.

### 3.6 Architecture
- Modules: `:app` (UI), `:engine` (pure), `:playback` (sources, router, crossfade), `:sensors`, `:data` (Room/DataStore), `:session` (recorder, recap), `:billing`.
- DI: Hilt (the compile-risk reason for hand-rolled DI is gone; project compiles).
- One `SessionViewModel` exposing `SessionUiState` (below). Compose Navigation with 3 top-level destinations + wizard graph + session full-screen route.

```kotlin
data class SessionUiState(
  val phase: Phase,                 // IDLE, STARTING, ACTIVE, ENDING
  val activity: Activity,
  val mode: ModeUi?,                // name, color, sentence, contributingSignals: Set<Signal>
  val band: IntRange, val override: OverrideMode,
  val nowPlaying: NowPlaying?,      // title, artist, art?, source, energy, progress, crossfading
  val timer: TimerUi?,              // remaining, phase, round/total
  val elapsed: Duration,
  val signals: Signals,             // motionRms, vertRate, speed, hrBpm, hrPct, hrTrend
  val fallback: FallbackEvent?,
)
```

### 3.7 Decision sentence
Replace debug strings (`RIDING after 3s: motion=3.1 …`) with human sentences from a `ReasonFormatter` that reads the `StateDecision.reason` structured form (change `reason: String` → `reason: Reason` sealed class with `toDebugString()`; keep a debug toggle in Settings "Show live signals"). Examples: "Descending fast with heavy motion for 3 s." · "Steady climb, barely moving, for 15 s." · "Pulse at 86% of max, so only your top tracks." · "No signals for 90 s, easing to mid."

## 4. Screens (map to `ActiPlayer Redesign.dc.html`)

| ID | Screen | Notes |
| --- | --- | --- |
| A1 | Welcome | Single CTA "Set up in 1 minute", restore link. |
| A2 | Activity | 2×3 cards; Pro activities show PRO badge for Free users, still selectable → paywall on Continue. |
| A3 | Music source | Local (default) vs Spotify (PRO). Local shows scan count. |
| A4 | Heart rate | Nearby BLE list, Garmin hint, Max HR stepper (default 220−age if age given, else 190). Skip link. |
| A5 | Permissions | One row per permission with plain reason; each row requests only its own permission. Bluetooth deferred if HR skipped. |
| B1 | Home idle | Activity card (Change), 96 dp Start, Ready check rows (Music pools, HR, Sensors) each deep-linking to fix, Last session. |
| B2/B3 | Session live | Full-bleed mode color; 72 sp mode name; sentence; 4 signal chips (lit = contributed); 5-segment band meter; bottom card: art, title, artist, energy, skip (56 dp), progress, CHILL/AUTO/HYPE (64 dp). Source line when Spotify or fallback. End = confirm sheet. Screen stays on while charging only; otherwise normal timeout. |
| B4 | Session live (timer) | SET/REST + 96 sp countdown + progress; PAUSE / SKIP REST. Tap anywhere ends set early (2 s undo toast). |
| B5 | Recap | Header, 3 mode-time tiles, timeline (mode segments + HR line + speed dashed), 3 stats, top tracks by mode. Share opens B6 preview → share sheet. |
| B6 | Share card | 1080×1350 PNG. |
| C1 | Music | 3 pool cards (Hype/Mid/Chill) listing sources as chips; unsorted banner → Quick sort. |
| C2 | Add to pool | Tabs Folders / Albums / Playlists / Spotify; multiselect; shows current pool membership; bottom CTA with track count. Podcast folders flagged. |
| C3 | Quick sort | Card stack, 30 s preview auto-seeks to loudest section (compute RMS envelope on decode; this is the hook for v2 section-seek). Swipe L=Chill, U=Mid, R=Hype, D=Exclude. Guess badge from BPM/genre tags. |
| C4 | Bulk edit | Multi-select list within a pool; move to Chill/Mid/Exclude. |
| C5 | Spotify | Connected status; per-level playlist pick (opens playlist picker from `/me/playlists`); fallback toggle; per-track rating toggle. Handles: not installed (Play Store link), Free account (explain), not logged in. |
| D1 | History | Month grouped list; each row: activity dot, title, meta, duration, mini timeline. |
| D2 | Paywall | Trial sheet; yearly preselected. |
| D3 | Settings | You / Playback / Detection / Account groups as drawn. |
| D4 | Timer setup | Presets + custom steppers; shown between Home Start and session for Strength/HIIT. |

Empty and error states to add (not drawn): no local music found; Spotify not installed / Free / offline; BLE lost mid-session (chip "HR —", no mode change); permission denied permanently (link to settings); pool too small (<8 tracks) warning on Home ready check.

## 5. Design system for implementation

- Type: Instrument Sans (UI), JetBrains Mono (numbers, labels, telemetry). Bundle as font resources.
- Scale: mode name 72 sp; countdown 96 sp; screen titles 26–30 sp; body 15–16 sp; mono labels 11–12 sp, letter-spacing 1.5 px, uppercase.
- Radii: cards 16–22 dp, chips 999, buttons 16 dp.
- Touch targets: in-session ≥ 64 dp; elsewhere ≥ 48 dp.
- Surfaces: ground → surface → surfaceHigh; 1 px outline. No shadows except the now-playing card in session.
- Mode color is the only saturated fill on session screens. Content on mode color is `ground` at 100% (sentences 85%).
- Motion: mode change = 300 ms background color tween + mode name crossfade; band meter segments animate 200 ms; override press = scale 0.96.

## 6. Build order (phase gates; compile + tests green at each)

1. **Restructure**: modules, Hilt, Room + DataStore, rename to ActiPlayer, Compose Navigation with 3 tabs. Port existing screens 1:1 into the new shell.
2. **Music pools + local source**: `Pool`/`TrackEnergy`, C1/C2/C4, dual-player crossfade with standby preload, energy resolution order.
3. **Session UI**: B1, B2/B3, `ReasonFormatter`, signal chips, end-session flow.
4. **Recording + History + Recap**: `SessionRecorder`, D1, B5, B6 share, JSON export.
5. **Wizard + permissions**: A1–A5; migrate first-run flag.
6. **Profiles**: Running, Generic, `TimerEngine` + D4 + B4; sensitivity setting; tests for each.
7. **Spotify**: App Remote + Auth, C5, `SourceRouter` with fallback, error states.
8. **Billing**: Pro entitlements, gates, D2 paywall, trial reminder.
9. **Quick sort**: C3 with 30 s preview and loudest-section seek; tempo guess from decoded audio (simple onset autocorrelation) or ID3 BPM tag.
10. **Release pass**: battery measurement (target < 8%/h with GPS + BLE), Play data-safety form (all data on device; Spotify token only), adaptive icon, screenshots from redesign, privacy policy URL, `targetSdk 35`, ProGuard, crash reporting (opt-in).

## 7. How to run this build with an implementation model

1. Clone the repo, open in Android Studio, confirm `./gradlew build` and `./gradlew :engine:test` pass on the current code.
2. Put this file in the repo root as `ACTIPLAYER_PLAN.md`; export the two design files as PNGs (or keep the HTML) into `design/`.
3. Update `CLAUDE.md` "Source of truth" to: 1) `ACTIPLAYER_PLAN.md`, 2) `DECISIONS.md`, 3) `README.md`. Delete the phase tracker; replace with §6 of this plan.
4. Start Claude Code (Opus) in the repo with: *"Read CLAUDE.md and ACTIPLAYER_PLAN.md. Execute build order §6 one phase at a time. After each phase: compile, run tests, install on my device, stop and wait for my on-device confirmation before the next phase. Log every deviation in DECISIONS.md."*
5. One phase per session. Phases 7 (Spotify) and 8 (Billing) need your credentials: Spotify developer app (client ID, redirect URI `actiplayer://callback`, package SHA-1) and Play Console products + license-tester account. Have those ready before starting those phases.
6. Give the model the screen IDs (A1…D4) when reviewing: "B2 spacing differs from the design" is enough.

## 8. v2 parking lot
- AI energetic-section seek: precompute per-track energy curve (RMS + spectral flux) on import; on up-switch start at the first high-energy section instead of 0:00. Toggle in Settings, Pro.
- Wear OS tile for glance + override.
- Light / high-contrast sun theme.
- iOS via Kotlin Multiplatform (`:engine` already portable).
- Threshold editor / debug dashboard + trace replay (from original brief).
