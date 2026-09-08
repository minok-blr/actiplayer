# DECISIONS.md

Per build brief §16: every non-obvious choice, one line + rationale. These are the MVP's
deliberate deviations from the brief; each has a defined path back to the full design.

- **No Hilt — hand-rolled `AppContainer`.** Annotation-processor/version-alignment issues
  are the top cause of first-build failures in a project that couldn't be compiled before
  delivery; a 40-line container carries zero of that risk. Migrate when the DI graph grows.
- **No Room — `SharedPreferences` for ratings.** Same KSP-avoidance rationale. Ratings are
  a flat `trackId -> Int` map today. Room returns with session recording (brief §9).
- **Heart rate via the standard GATT Heart Rate Profile (0x180D/0x2A37), no Garmin SDK.**
  A Garmin in Broadcast HR mode is just a generic BLE strap; the same client covers
  Polar/Wahoo/Coospo for free — exactly the roadmap in brief §11.
- **No `connectedDevice` foreground-service type.** On API 34+ each declared FGS type's
  prerequisites are checked at promotion time, and BLUETOOTH_CONNECT is a runtime grant.
  HR is optional, so a user who declined Bluetooth must not crash session start. A live
  GATT connection survives fine under the existing `mediaPlayback|location` FGS.
- **Legacy descriptor-write API (suppressed deprecation) for enabling HR notifications.**
  One code path that works uniformly on API 29–35 beats branching on the API 33 overloads.
  Both characteristic-changed callbacks ARE implemented, since the framework calls
  different ones by API level.
- **Bluetooth permissions requested lazily, at HR setup — never at app start.** Sessions
  never require them; graceful degradation to motion-only is principle §2.3.
- **HR modulation lives in the engine (pure Kotlin), not the coordinator.**
  `energyFor(state, frame)` narrows the band within a mode (RIDING + ≥85% max → top
  energy only; PAUSED + falling trend → calm end) and can never change the mode. The
  `settle()` path emits a fresh decision only when the modulated band actually changes.
  No threshold hysteresis needed: narrowing can force at most one swap, widening never
  swaps a fitting track. Unit-tested, including the "HR never changes the mode" invariant.
- **HR disconnects at session stop.** Saves watch and phone battery between sessions;
  reconnect at next session start is automatic via the remembered address.
- **Single-player volume-ramp fade instead of dual-player crossfade + standby preload.**
  Roughly a third of the audio code for 80% of the feel; the brief's §8 design is the
  target. Up-switch still cuts fast (250 ms), down-switch still eases (1.5 s).
- **Framework `LocationManager` (GPS provider) instead of Play Services fused location.**
  Removes an entire dependency; 1 Hz GPS during an outdoor sports session is exactly the
  case where raw GPS is fine.
- **No gyro/heading features in the MVP frame** (`rotRms`, `headingStability` from §6
  dropped). LIFT detection rests on barometric climb + stillness, which is the strongest
  part of the fingerprint anyway; add heading stability when tuning against real traces.
- **Session requires audio + location permissions before starting.** On API 34+, starting
  a foreground service declared with the `location` type without the permission throws.
  Gating up front turns a crash into a clear explainer screen.
- **`startService()` (not `startForegroundService()`) to launch `PlaybackService`.** The
  app is in the foreground when a session starts, and Media3 performs the foreground
  promotion itself when playback begins — calling `startForegroundService` ourselves would
  race Media3's `startForeground` call against the 5-second deadline.
- **Everything on the main dispatcher** (sensors, engine, player commands). At 1 Hz frame
  rate this is nowhere near saturating the main thread, and it deletes all
  synchronisation concerns. Revisit only if profiling ever says so.
- **Engine treats dwell as pre-satisfied on the first frame of a session.** Otherwise a
  rider who starts the session at the top and drops straight in would be locked in PAUSED
  for 20 s. Matches the spirit of brief §2.5 (up-switches must feel instant).

## 2026-07-08 — UI rework (P6, first pass)

- **Session tab split into idle "launch checklist" vs. active "Ride Board".** Brief §2.2
  says in-session interaction count should be zero, so the two contexts get two layouts:
  idle = start button + readiness cards (library/HR/sensors) + a dismissible quick guide;
  active = full-bleed mode-colored block, 5-segment energy meter, big bottom override
  chips (64 dp, thumb zone), glove-scale targets.
- **Dark-only theme with exact mode hues** (RIDING #FF5A1F / LIFT #4FC3F7 / PAUSED
  #7E8AA0 on #0B0E14; `ui/Theme.kt`). Sunlight + goggles want maximum contrast, and mode
  color is the primary state channel, so dynamic color is off and there is no light theme.
- **Live telemetry demoted behind a "Signals" toggle on the Ride Board.** It's a tuning
  tool, not rider information. The decision reason stays always-visible inside the mode
  block — it's the trust mechanism.
- **First-run quick guide as a dismissible card on the idle screen** (flag in
  SettingsStore), not a modal onboarding flow. Re-openable via "Show quick guide".
- **Library filter chips (All / Unrated / 1–2 / 3 / 4–5) with counts + compact one-line
  rating rows.** Real devices mix podcasts into MediaStore "music" (a test device had 164
  unrated episodes vs 15 rated tracks); filters make rating workable. Track *exclusion*
  deliberately deferred — it changes pool semantics vs. the brief's "unrated = 3" rule;
  needs the brief before committing.

## 2026-07-08 — Activity profiles (cycling first)

- **`Activity` enum + `ActivityEngine` interface in :engine; engine chosen per session.**
  Snowboarding keeps `StateEngine`; cycling gets `CyclingEngine`. Running/hiking appear
  in the menu as disabled "soon" placeholders so the direction is visible in the UI.
- **Cycling lets HR help DECIDE the up-switch — a deliberate break from the snowboard
  invariant** ("HR never picks the mode"), at the product owner's request. Rationale: on
  a bike, effort and rising HR are near-simultaneous (no chairlift-style lag trap), and
  requiring speed-gain AND rising HR kills false positives from downhill speed gains the
  rider isn't working for. When no monitor is paired, speed evidence stands alone.
- **Cycling is a 2-state machine (CRUISE/EFFORT), not 3.** ≤15 km/h (incl. stopped at a
  light) = CRUISE, chill band 1–2; a detected push = EFFORT, band 4–5. Fast-but-steady
  keeps whatever state got you there (natural hysteresis) instead of inventing a third
  mid band — spec only defined chill and hype; revisit after real rides.
- **One flat `RideState` enum for all profiles** (RIDING/LIFT/PAUSED + CRUISE/EFFORT)
  rather than per-profile decision types: keeps `StateDecision` and the UI simple; each
  engine emits only its own subset.
- **All cycling thresholds live in `EngineConfig`** (`cycling*` fields): chill ≤4.17 m/s,
  effort = +1.2 m/s over a 6 s window, HR trend ≥ +3 bpm/min when present, confirm 3 s
  up / 20 s down, dwell 15 s / 10 s. 9 new unit tests cover start state, red lights,
  effort with/without monitor, HR-flat downhill rejection, fast-steady no-fire, short
  coast survival, sustained return to cruise, and redline narrowing.

## 2026-07-09 — Remote tab (Spotify + SoundCloud, sample)

User-requested sample: a third "Remote" tab that reaches Spotify and SoundCloud playlists.
Both services DRM-lock their audio to their own players, so Remote is browse-and-hand-off,
not engine-driven playback — the adaptive engine still runs only on the local rated
library, and the tab says so in its header.

- **Spotify: raw Authorization Code + PKCE against the Web API, no Spotify SDK.** Verified
  against current docs (2025 security update): PKCE is the mandated flow for clients that
  can't hold a secret, and custom-scheme redirects remain supported. One browser
  round-trip + two HTTPS calls via `HttpURLConnection` + `org.json` keeps the sample at
  zero new dependencies. Redirect URI `flowstate://spotify-auth` lands in a `singleTask`
  MainActivity (`onNewIntent`, plus `onCreate` for cold-start redirects); the PKCE
  verifier is persisted in prefs so the handshake survives process death during the
  browser trip.
- **The user pastes their own Client ID** (free developer.spotify.com app); nothing is
  baked into the APK, and dev-mode Spotify apps only authorize the owner's account anyway
  — fine for a personal build. Stored with the tokens in a separate `remote` prefs file.
- **Playlists open in the Spotify app** (`https://open.spotify.com/playlist/…`); listing
  is read-only (`playlist-read-private` + `playlist-read-collaborative`), capped at 200.
- **SoundCloud: keyless embedded widget player in a WebView**, fed by a pasted
  playlist/track URL. Registering for real API credentials now requires an Artist Pro
  subscription (verified 2026-07), which is out of scope for a sample; the public
  `w.soundcloud.com/player` embed needs no key and actually plays audio in-app.
- **INTERNET permission added, scoped by comment to the Remote tab.** Everything else
  (engine, sensors, local playback) remains offline by design.
- Path back to the full design: if remote playlists should ever feed the engine, that
  means Spotify App Remote SDK control (Premium-only) or SoundCloud API streams with
  per-track energy ratings keyed by URL — both deliberately out of scope today.

## 2026-07-09 — Spotify adaptive playback architecture (awaits Premium test)

User-requested: the full rate-by-energy + engine-driven playback loop for Spotify,
testable once a Premium account is available.

- **Spotify Connect (Web API player endpoints) instead of the App Remote SDK.** Zero new
  dependencies — reuses the Remote tab's PKCE tokens and HTTP plumbing; scopes extended
  with user-read/modify-playback-state. Verified current: PUT /me/player/play with a
  `uris` array, Premium-only, not deprecated. If command latency over the network
  disappoints on the road, the swap to App Remote (local IPC) is contained behind
  `PlaybackBackend` — that's the logged migration path.
- **`PlaybackBackend` interface** (onBand/skip/stop): `QueueController` (local, full
  fades) and `SpotifyQueueController` implement it; `SessionCoordinator` picks per
  session from the new Music source setting (LOCAL | SPOTIFY, Session-tab card). The
  engine cannot tell backends apart.
- **Band changes are batches, not single tracks.** Each band change sends one play
  request with up to 40 shuffled in-band track URIs; Spotify auto-advances through them,
  so steady riding costs zero API traffic and there is no track-end handling. Selection
  mirrors QueueController (±1 widening under MIN_POOL=8, whole pool as last resort) as a
  pure `batchFor` function — 6 JUnit tests (`:app:testDebugUnitTest`, first app-module
  unit tests).
- **Hard cuts, by service design.** Spotify Connect has no volume ramp, so the sacred
  asymmetric fades exist only on the local backend. Accepted and surfaced in the UI copy.
- **Spotify pool = rated tracks only** (`SpotifyRatingsStore`, uri → {title, artist,
  rating}). No enumerable "all my Spotify music" exists, so the unrated-defaults-to-3
  policy can't apply. Rating UI lives in the Remote tab (expand a playlist → 1–5 boxes).
- **Sensor FGS without local playback:** Media3 only promotes PlaybackService while it
  plays audio, so Spotify sessions start the service with ACTION_SENSOR_FOREGROUND and it
  takes a location-type foreground itself (small "Reading sensors" notification) — GPS
  and motion survive the pocket either way.
- **404 (no active device) is handled** by retrying against this phone's device id from
  /me/player/devices; 403 surfaces as "needs Premium" on the Ride Board. Not yet
  verifiable end-to-end without Premium — the pure selection logic is unit-tested and
  everything else compiles + runs; the human test script: connect Spotify in Remote
  (client ID + new scopes), rate ~10 playlist tracks across bands, set Music source =
  Spotify, open the Spotify app once, start a session, shake/still the phone and watch
  the Spotify app switch batches.

## 2026-07-09 — UI overhaul (music-app design language)

User-directed: restyle after Spotify/SoundCloud and the best music players; keep it
user-friendly and intuitive. Verified on-device (idle, Library, Remote, Ride Board,
mini player).

- **Bottom navigation bar** (Ride / Library / Remote, material-icons-extended) replaces
  the top TabRow — thumb-reach navigation is the music-app standard. The quick-guide "?"
  moved into the Ride screen's header.
- **Mini player bar** docked above the bottom nav whenever a session is active and
  another tab is open: mode-color dot, now-playing title, state + band line, skip button;
  tapping it returns to the Ride Board. Session control is never more than one tap away.
- **Screen headers**: Spotify-style heavy `headlineLarge` titles with quiet subtitles
  (`ScreenHeader` in the new `ui/Components.kt`).
- **Generated artwork tiles** (`ArtTile`): local files have no cover art, so rows get a
  gradient tile in the track's energy color (chill ice / neutral slate / send-it orange
  via `energyTint`); unrated stays neutral as a nudge to rate. Used in Library, Remote
  playlists, Ride Board now-playing.
- **Shared `RatingBoxes`** control replaces the three per-screen copies; selected box
  takes the energy color, not always orange.
- **Ride Board**: mode color now washes down the whole screen (30% -> ground vertical
  gradient, album-page style) on top of the existing solid mode block (kept — goggles
  readability rule); skip is a big circular icon button.
- **Theme**: heavier display typography; service brand colors (Spotify green #1DB954,
  SoundCloud orange) added but used only to badge the services themselves — the mode
  hues remain the app's only saturated channel elsewhere.
- Dependency added: `androidx.compose.material:material-icons-extended` (BOM-managed;
  R8 strips unused icons in release).

## 2026-09-08 — Phase 1: restructure (modules, Hilt, Room + DataStore, 3-tab shell)

Plan §6.1. Everything below is a deliberate deviation or a technical call made under
"technical ambiguity -> decide, log here".

### Toolchain

- **Kotlin 2.0.21 -> 2.3.21, compileSdk 35 -> 36; targetSdk stays 35.** Not optional:
  Room 2.7+ ships `kotlin-stdlib` 2.1.10 and Room 2.8 ships 2.2.0, and a Kotlin 2.0.21
  compiler cannot read metadata from either. Staying on 2.0.21 would have pinned the
  project to Room 2.6.1 + Hilt ~2.52 (2023-era) as the foundation for nine more phases.
  targetSdk is a plan §1 decision and was left alone; compileSdk is not, and compiling
  against the latest platform the toolchain supports is standard practice.
- **Held at AGP 8.13.2 / Gradle 8.13, which caps the dependency set.** AGP 9.4 is out and
  the newest AndroidX (compose 1.12, core-ktx 1.19, navigation 2.10, lifecycle 2.11,
  hilt-navigation-compose 1.4) now *requires* AGP 9.1 + compileSdk 37 — neither of which
  is installed here, and AGP 9 carries its own DSL migration. So the catalog is pinned to
  the newest release of each library that still declares AGP 8.13 support: compose BOM
  2026.05.01, core-ktx 1.18.0, lifecycle 2.10.0, navigation-compose 2.9.8,
  hilt-navigation-compose 1.3.0, Room 2.8.4, media3 1.11.0, datastore 1.2.1.
  The AGP 9 + SDK 37 jump is a phase-10 item, not a phase-1 one.
- **Hilt 2.58, not 2.60.1.** Hilt's Gradle plugin from 2.59 on refuses AGP < 9.0
  outright (2.59 additionally calls an AGP 9 API at configuration time). 2.58 is the last
  release that works here. Verified by bisecting, not from memory.
- **`material-icons-extended` pinned to 1.7.8.** It was frozen there and dropped from the
  Compose BOM, so it can no longer be BOM-managed. Migrating off it is a later cleanup.

### Modules

- **`:app`, `:engine`, `:data`, `:sensors`, `:playback`, `:session` — no `:billing` yet.**
  The plan's §3.6 list includes `:billing`, but there is nothing to put in it until
  phase 8, and an empty module is a stub with a build cost. It gets created with its
  first real content.
- **Package per module** (`com.flowstate.data`, `.sensors`, `.playback`, `.session`),
  still under `com.flowstate.*` as plan §1 requires. `applicationId` is unchanged, so
  this is not a user-visible reinstall.
- **`SpotifyRemote` lives in `:playback`, not `:data`.** It is the transport that actually
  plays Spotify tracks; the ratings for those tracks are in `:data`. That direction keeps
  `:data` free of any service SDK.
- **Explicit per-module Gradle files instead of a convention plugin.** Five modules of
  ~20 near-identical lines is less machinery than a `buildSrc` convention plugin and has
  no configuration-cache/isolated-projects risk. Revisit if the count grows.
- **`PlaybackService` is declared in `:playback`'s manifest; every permission stays in
  `:app`'s.** The component travels with its code, but the app's permission inventory
  stays readable in one file — which is what the phase-10 Play data-safety form needs.

### DI

- **Hilt replaces the hand-rolled `AppContainer`** (the 2026-07 "no Hilt" decision is
  retired: the project compiles, so the first-build-reliability argument is spent).
  Constructor injection throughout; `SingletonComponent` only.
- **No ViewModels yet.** `MainActivity` is `@AndroidEntryPoint` and passes the injected
  singletons into the composables exactly as `AppContainer` did — a true 1:1 port. The
  plan's single `SessionViewModel` + `SessionUiState` is phase 3's deliverable, and
  writing throwaway ViewModels now would be churn against screens that phase 3 replaces.
- **One application-scoped `CoroutineScope` on `Dispatchers.Main.immediate`**
  (`@ApplicationScope`), replacing the per-object scopes. Keeps the standing
  "everything on the main dispatcher" decision intact; Room and DataStore suspend into
  their own executors from there.

### Data

- **Room schema v1 is `tracks` + `track_energy` only.** Plan §3.1's `Pool`/`PoolSource`
  arrive in phase 2 and `Session`/`SessionEvent`/`TimerPreset` in phase 4, each as a
  numbered migration against the schema exported to `data/schemas/`. Modelling them now
  would guess at shapes those phases will change.
- **One synthetic track id for both sources** — `local:<mediaStoreId>` / `spotify:<uri>`.
  Ratings, and later pools and session events, reference either source through one
  column instead of two parallel tables.
- **`band: Int?` with null meaning EXCLUDED**, distinct from "unrated" (no row at all).
  One column, no way for two fields to disagree. No UI for exclusion yet — that is
  phase 2's C3/C4.
- **No foreign key from `track_energy` to `tracks`.** Local tracks are re-scanned from
  MediaStore and only need a `tracks` row once pools/history reference them, so a rating
  legitimately exists before its track row does.
- **Legacy ratings are imported, not dropped.** `LegacyRatingsImport` reads the old
  `ratings` and `spotify_ratings` SharedPreferences files once (guarded by a DataStore
  flag) and writes them into Room. The old files are deliberately left on disk: a few
  kilobytes, and the only way back if the import is ever wrong. `SettingsStore` migrates
  via DataStore's own `SharedPreferencesMigration`, preserving key names.
- **`SettingsStore` blocks once on its first DataStore read.** The session loop reads
  `maxHr.value` from a non-suspending 1 Hz tick and the screens read settings during
  composition, so the API stays `StateFlow` + fire-and-forget writes. Without the one
  blocking read, a user who dismissed the quick guide would see it flash back on every
  launch while DataStore loaded. Path back: screens read flows once phase 3's ViewModel
  lands.
- **Session start reads ratings with `snapshot()`, straight from the DAO**, not from the
  observable cache — a session must never begin with a half-populated pool.

### Shell

- **Three tabs Home · Music · History, custom bottom bar.** The redesign (D1) draws
  outline geometric icons with a short underline under the selected label, not Material's
  pill indicator — a pill would put a second saturated shape on screens where mode color
  is meant to be the only one.
- **Screens ported 1:1: Home = the existing Session screen, Music = the existing Library
  screen.** B1/B2/B3 are phase 3 and C1/C2/C4 are phase 2; redrawing them now would be
  work thrown away twice.
- **History is an empty state, not a list.** Nothing records sessions until phase 4, and
  a fake list would be a stub presented as done.
- **The old "Remote" tab became a route under Music** (`music/spotify`, reached from the
  Music header). It is working functionality that the 3-tab map has no room for; phase 7
  replaces it with C5 on the App Remote SDK. The `flowstate://spotify-auth` redirect URI
  is unchanged — it is registered in the user's Spotify developer app, and plan §7 moves
  it to `actiplayer://callback` when that phase rewires auth anyway.
- **Guide and permission gates stay full-screen takeovers rather than nav destinations**,
  which is what they were. Phase 5 replaces both with the A1–A5 wizard.
- **`FlowColors`/`FlowTheme` renamed to `ActiColors`/`ActiTheme`; palette byte-identical.**

### Repo hygiene

- **`.gitignore` now covers `build/`, `.gradle/`, `.kotlin/`, `.idea/`, `local.properties`.**
  1812 of the repo's 1851 tracked files are build output committed in the initial commit,
  which would bury every phase diff. Untracking them rewrites the index, so it is left as
  a one-line command for the human rather than done unasked.
