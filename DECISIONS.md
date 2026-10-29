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

## 2026-09-08 — Phase 2: music pools + local dual-player crossfade

Plan §6.2. Screens C1, C2, C4; `Pool`/`TrackEnergy`; energy resolution order; the real
crossfade with a preloaded standby track.

### Pools and energy resolution

- **Room schema v2 adds `pool_sources`, via Room's own `@AutoMigration`.** Adding a table
  is a pure addition, so Room generates and validates the SQL against the exported v1
  schema rather than hand-written DDL that can drift. Verified on-device: the Galaxy A52s
  upgraded 1 -> 2 with no data loss.
- **A source lives in exactly one pool** (primary key `(kind, key)`). Adding a folder that
  is already in Hype to Mid *moves* it. That is what C2's "IN MID" badge and C4's move
  buttons both imply, and it removes a whole class of "why is this in two pools" bugs.
- **Resolution order: exclusion -> user rating -> playlist -> album -> folder -> unsorted.**
  Plan §3.1 specifies user rating -> pool membership -> tempo guess -> 3. Two additions,
  both forced by real behaviour:
  - *Exclusion outranks everything.* Otherwise a pooled folder silently drags an excluded
    track back in, and "never play this" would not hold.
  - *Pool sources are ranked playlist > album > folder,* because a track can be in a
    pooled album inside a pooled folder. A playlist is the most deliberate thing a user
    builds; the folder a file happens to sit in is the least.
  The tempo guess is phase 9, so an unrated, unpooled track is **unsorted and never
  plays** — which is the point of pools (a test device has 178 tracks of which 13 are
  podcasts).
- **Empty-pool fallback: everything non-podcast at band 3.** A brand-new user with no
  pools and no ratings must not meet silence. `SessionPool.fallback` flags it, and Home's
  readiness card says so. Without this, phase 2 would have been a regression for anyone
  who had not yet built a pool.
- **`MediaStore`'s `IS_PODCAST` is not enough to flag podcast folders.** It is essentially
  never populated on real devices (the test phone's 13-track `Podcast` folder had it
  unset), so the C2 warning never fired. A folder path containing "podcast" now counts
  too — a heuristic, but the one that actually works.
- **Device playlists are read through the deprecated `MediaStore.Audio.Playlists`.**
  Deprecated in API 31 (verified against the SDK's own `api-versions.xml`); reads still
  work, writes by third-party apps do not. Most phones have none, so the Playlists tab
  has an empty state that explains why rather than looking broken.
- **The C2 Spotify tab is deferred to phase 7,** which is where Spotify playlists get
  their auth and picker. A fourth tab that only says "later" is a stub.
- **C1's "Quick sort" button opens C4 filtered to Unsorted** instead of the phase-9 swipe
  UI. A dead button would be worse; bulk-moving unsorted tracks is real work the user can
  do today, and phase 9 replaces the destination.
- **The flat "Library" screen is retired.** C1 + C4 cover browsing and rating: C4's
  filter pills (Hype / Mid / Chill / Unsorted / Excluded) are the old filter chips with
  pool semantics behind them. Per-track 1–5 granularity stays in the data and comes back
  in C3 (phase 9); C4 writes the level bands 5 / 3 / 1.

### Local playback

- **`MusicSource` is the interface the plan names (§3.2), so the settings enum
  `MusicSource` was renamed `SourceKind`.** Both names cannot be right; the interface is
  the one the plan and the phase-7 `SourceRouter` depend on.
- **Two ExoPlayers, and this class owns the audio focus — not the players.** Two players
  each handling their own focus actively fight: the second one's request makes the first
  lose focus and pause, which is exactly the moment a crossfade needs both audible. So
  both are built without focus handling and `LocalSource` holds one `AudioFocusRequest`
  for the pair, plus the becoming-noisy receiver.
- **The MediaSession follows the audible player** (`MediaSession.setPlayer` on each swap,
  verified present in Media3 1.11.0). Without it the notification and lockscreen would
  show the outgoing track for the length of every fade. The session switches at the
  *start* of the fade, since the incoming track is what the listener is about to hear.
- **The standby player serves down-switches too, not only up-switches.**
  `standbyBand()` preloads the opposite band, which is exactly where a down-switch is
  heading — refusing to use it there would have thrown away the preload half the time.
- **`TrackPicker` is a pure object, not a method on the source.** It is the rule that
  decides what the athlete actually hears, so it is unit-tested (6 tests) rather than
  desk-checked. `QueueController` is gone; its selection logic lives here.
- **Crossfade is 1–3 s from settings for down-switches and track ends; up-switches use a
  fixed 300 ms.** Plan §1 and §3.2. The stepper lives on Home next to the other session
  cards until D3 Settings lands.
- `ExoPlayer.Builder.setLooper` is `@UnstableApi` and opted into deliberately: pinning
  both players to the main looper is the point, since one coroutine drives both.

### Correction to phase 1

- **`LegacyRatingsImport` no longer marks itself done when it found nothing.** It set the
  flag unconditionally, so a device that had no legacy preference files at first launch
  would ignore them forever if they later reappeared (restore from backup, or a reinstall
  that puts them back). Now the flag is only written when something was actually imported.

## 2026-09-08 — Podcasts hard-excluded; Walking profile added

Both user-requested, between phases 2 and 3.

- **Podcasts are dropped by the resolver itself, before anything else is considered.**
  Previously they were merely *unsorted* — silent in practice, but they still counted in
  C1's "unsorted tracks" banner, still padded pool chip counts, and would have played if
  their folder were ever pooled. Now `PoolKeys` carries `isPodcast` and `EnergyResolver`
  drops them ahead of the exclusion and rating checks, so not even an explicit 5-star
  rating can put an episode mid-run. They stay *visible* in the C2 browser, badged
  `13 PODCASTS · SKIPPED`, because a folder silently missing from the picker is worse
  than one that explains itself.
- **`Activity.HIKING` became `Activity.WALKING`.** HIKING was a placeholder inherited
  from the original FlowState brief and is not in the ActiPlayer plan's activity list
  (§1), so nothing was lost by reusing the slot for a profile that is actually
  implemented. A stored `HIKING` value fails `Activity.valueOf` and falls back to
  SNOWBOARDING, which the settings store already handles.
- **Walking is a 2-state profile (STROLL / BRISK), matching cycling's shape.** Brisk =
  over ~5.4 km/h *or* a clear stride in the accelerometer; stroll = under ~4 km/h and
  calm, which also covers waiting at a crossing. Asymmetric hysteresis as everywhere
  else: 3 s up, 20 s down.
- **HR co-decides the walking up-switch, and the bar is absolute bpm (78), not %max.**
  Two deviations, both deliberate:
  - The snowboard invariant ("HR never picks the mode") is broken here for the same
    reason it is for cycling: walking speed sits close to the GPS noise floor, so speed
    alone is weak evidence, and a rising pulse is what separates striding out from
    drifting downhill. With no monitor paired, movement evidence stands alone.
  - A percentage-of-max threshold would never be crossed at walking intensity — 78% of a
    190 max is 148 bpm, which is running. The user specified ~75–80 bpm; `EngineConfig`
    holds it as `walkingBriskHrBpm` so it is tunable per person.
- **Motion RMS can carry the walking up-switch with no GPS fix at all.** This is what
  makes the profile testable indoors — a corridor with the phone pocketed — which was the
  reason it was requested.
- 10 unit tests cover start state, the HR gate (walking fast with a resting pulse must
  *not* fire), no-monitor operation, the no-fix indoor path, a road crossing not ending a
  brisk walk, sustained ambling returning to stroll, a pulse dropping back ending it,
  redline band narrowing, and that HR alone never moves the mode.

## 2026-09-08 — Phase 3: session UI (B1, B2/B3, ReasonFormatter, signal chips)

Plan §6.3. The screens the app is actually for.

### Explaining the engine

- **`StateDecision.reason` is now a `Reason` sealed class, not a string** (plan §3.7).
  The old debug line (`RIDING after 3s: motion=3.1 vert=-2.4 …`) could not be turned back
  into a sentence or into a set of lit chips without parsing it, so the engines now emit
  structure: which state confirmed, how long it held, and an `Evidence` set naming the
  conditions in human terms. `toDebugString()` keeps the original telemetry, behind the
  new "Show live signals" toggle.
- **`Evidence` carries the signal it came from**, which is what lights B2/B3's chips.
  A chip is dark when that signal actually contributed and washed-out when the engine read
  it and moved on — so the screen distinguishes "HR is 164" from "HR is why".
- **The engines' behaviour is unchanged.** All 30 existing engine tests passed untouched
  through the refactor, which was the point of doing it as a pure representation change.
- **`ReasonFormatter` lives in `:app`, not `:engine`.** The sentences are user-facing copy
  and belong next to the UI; `:engine` stays free of anything that reads like product
  voice. It is unit-tested (9 tests), including one that walks every `RideState` and fails
  if any of them ever leaks a bare enum name onto the biggest text on the screen.

### Architecture

- **`SessionViewModel` + `SessionUiState` landed here, as phase 1 deferred.** The screens
  read nothing but `SessionUiState` — not the coordinator, not the engine, not a player.
  That is what makes B2/B3 identical whether the music is local or Spotify, and it is why
  phase 7's `SourceRouter` will need no UI changes at all.
- **Progress and elapsed time are polled at 2 Hz, not pushed.** They are the only things
  that move between 1 Hz frames; a `progress()` call on the active source is cheaper than
  mirroring player state into a flow, and an idle session ticks nothing.
- **The session is its own full-screen route, not the Home tab.** Mode colour has to reach
  every edge (plan §4), so the route carries no bottom bar. Back returns to the tab shell,
  where the mini player is the way in again. A latch guards the auto-pop: the view model
  publishes an IDLE placeholder before its first real emission, and popping on that closed
  the route the instant it opened.

### Screens

- **Fonts are bundled, not downloaded** — Instrument Sans and JetBrains Mono TTFs in
  `res/font` (both SIL OFL). A session screen has to render correctly on a mountain with
  no signal, so a downloadable-font provider was never an option.
- **B1's "Last session" is an empty-state card.** Recording starts in phase 4; the card
  keeps the design's shape rather than pretending or leaving a hole.
- **Settings live behind the avatar as a sheet**, holding music source, crossfade, max HR
  and the signals toggle — the controls phases 1–2 had inline on Home. Screen D3 proper
  comes with the wizard; this is the smallest thing that keeps them reachable.
- **The heart-rate setup was moved wholesale into a sheet off the ready-check row.**
  Bluetooth-off and missing-permission are the two setup traps and that code already
  handles both, so it was carried over intact rather than rewritten.
- **`RideState` gets display names in the UI layer** — PAUSED reads as BREAK (plan §3.3),
  and each profile's states keep their own words. The engine enum is unchanged.
- **The screen stays awake only while charging** (plan §4). On battery it times out
  normally: a session runs for hours in a pocket and a permanently lit screen would be
  the single largest drain on the device.

### Verified on device

Start → full-screen session → HYPE override (band meter to 4–5, media session switched to
a Hype-pool track, mode correctly unchanged) → End → confirm sheet → back to Home with the
session torn down. The A52s has no barometer, so the sensors row reads "MOTION · GPS",
which is the truth rather than a bug.

## 2026-09-08 — Heart-rate connection fixes (reported on device)

Two bugs, one long-standing and one introduced in phase 3.

- **Session start was destroying a working heart-rate connection.**
  `SessionCoordinator.startSession()` calls `heartRate.connect()` unconditionally, and
  `connect()` went straight to `establish()`, which tears the GATT link down before
  rebuilding it. So connecting the strap from the setup sheet and *then* starting a
  session dropped the live link and restarted the whole connect dance — service
  discovery, notification enable, first notify — leaving the HR chip empty for seconds
  or for good. `connect()` now leaves an existing connection alone.
- **`connect(force = true)` for the "Connect now" button.** The guard above must not
  apply to an explicit user request: that button is the only way to break a connect
  attempt that has silently stalled, and a guarded version of it would have done nothing
  at exactly the moment the user needed it. Session start passes no flag; the button
  forces.
- **The heart-rate sheet was reading bpm out of `FeatureFrame`s, which only exist during
  a session.** Introduced in phase 3 when `HeartRateCard` was moved into a sheet and its
  `lastFrame` parameter was reduced to `bpm`/`pctMax` off `SessionUiState`. Outside a
  session there are no frames, so the card said "waiting for data" forever — precisely
  when someone opens it to check the strap works. It now reads `monitor.readings`
  directly, which is live regardless of session state.
- **B1's heart-rate ready row shows live bpm** when connected, so the strap can be
  checked at a glance without opening the sheet or starting a session.
- **The signal-chip row was overflowing and silently clipping the HR chip.** This was the
  actual cause of the reported "HR field is empty during a session": instrumenting the
  pipeline showed heart rate reaching every frame correctly (bpm=50-52, buffer filling)
  while the chip rendered as a bare `HR`. Four chips at intrinsic width just exceed this
  screen, and a Compose `Row` clips rather than shrinking — and the HR chip is last, so it
  lost its value *and* its "—" placeholder, which is why it read as no data rather than as
  broken layout. Tighter chip metrics so they fit, plus `horizontalScroll` so the row can
  never silently lose content on a narrower device.

  Worth recording that the first two fixes were found by reading the code and were both
  real, but neither was the visible symptom. The chip bug was only found by measuring
  pixel geometry in a screenshot and then instrumenting the data path — the lesson being
  that "the value is missing" is a claim about rendering until the data path is checked.

## 2026-09-09 — Phase 4 (reduced): recording, History, Recap

Plan §6.4, deliberately scoped down at the product owner's direction: `SessionRecorder`,
D1 and B5 only. **The B6 share card and JSON export are deferred to the phase-10 release
pass.** The reasoning was that phase 4's value here is the tuning loop, not the sharing —
every threshold in `EngineConfig` is a starting value and there is currently no way to
judge one except by watching the screen and forming an impression.

- **Room schema v3 adds `sessions` + `session_events`**, both pure table additions, so
  Room generates and validates the migration (`AutoMigration(2, 3)`) against the exported
  v2 schema. Third migration in a row done this way; no hand-written DDL yet.
- **One events table with a `kind` discriminator**, per plan §3.1, rather than four narrow
  ones. The recap reads the whole timeline in order, so keeping it in one table makes that
  a single query.
- **`OverrideMode` is stored by name, not as a typed column.** It lives in `:session`,
  which depends on `:data` and not the reverse; storing a string beats inverting the
  module graph for one column.
- **Overrides are recorded and surfaced as a recap stat, which the plan does not ask for.**
  Every override is the athlete correcting the engine by hand, so the count is the most
  honest score the thresholds can get. It sits alongside AVG HR / TOP SPEED / SWITCHES in
  a scrollable row rather than replacing one of the three the design draws.
- **Samples are stored at 5 s, not 1 Hz** (plan §3.4): enough resolution for the recap's
  HR and speed lines at a fifth of the rows.
- **Events written before the session row exists are held and flushed.** Room assigns the
  session id asynchronously; everything here runs on the main dispatcher so the pending
  list needs no synchronisation.
- **Empty sessions are pruned**, at both start and stop. A crash or force-stop leaves a
  session with no end timestamp, and a session with no events at all is noise in the
  history list rather than history.
- **The recap is deliberately not a fitness log.** The user wears a Garmin, which already
  records distance and pace and does it better. B5 leads with the mode timeline, mode-time
  tiles and top tracks per mode — what the *app* did — and keeps HR and speed as context
  lines on the timeline rather than headline numbers. Duplicating a watch would make it a
  worse watch.
- **Durations switch between `H:MM` and `M:SS` under an hour.** A twenty-minute walk with
  every mode tile reading `0:00` would make the recap useless, which is exactly the length
  of session being used to test the engine right now.
- **Locale is read observably** (`LocalConfiguration.current.locales[0]`) rather than via
  `Locale.getDefault()` inside composables, which lint correctly flags: the latter does
  not recompose when the device locale changes.

## 2026-09-09 — Phase 5: onboarding wizard + per-signal permissions (A1–A5)

Plan §6.5. The wizard replaces both the old quick-guide takeover and the blunt
"grant everything at launch" wall.

- **First-run flag migrated, not reset** (the plan's explicit instruction).
  `onboardingComplete` falls back to the old `guideDismissed` value, so anyone who had
  already dismissed the pre-wizard guide is treated as onboarded and is not dropped into
  a setup flow by an update. No migration code — just the fallback in the accessor.
- **Permissions are requested one signal at a time, from a row that states the reason in
  the athlete's terms.** "Speed and altitude tell a run from a chairlift" rather than
  "ACCESS_FINE_LOCATION". Nothing is asked for at launch any more.
- **Bluetooth is genuinely deferred when the heart-rate step is skipped** (plan §4, A5):
  the row is not shown at all, and A5 says so rather than leaving a silent gap.
- **A4 asks for Bluetooth on entry, not on tap.** Scanning is the entire purpose of that
  screen, so the request is already in context — which is the thing the old wall was not.
- **The old launch-time permission gate became a repair screen that reuses A5.** The
  standing decision (session requires audio + location before starting, or the
  location-typed FGS throws on API 34+) is unchanged; what changed is that a returning
  user who revoked something now gets the explained rows instead of one opaque button.
  `StepScaffold` takes a nullable step index so the repair screen carries no wizard
  progress bar.
- **`sessionPermissions()` no longer includes notifications.** The old gate demanded them
  to start; a session runs perfectly well without a notification, and blocking on one was
  the gate overreaching.
- **A2 shows four activity cards, not the plan's 2×3.** Strength, HIIT and Generic have no
  detection profile until phase 6, and three unselectable cards would be half the screen
  doing nothing. The grid fills out when those profiles land.
- **No PRO badges or paywall on A2/A3, and no "I have an account · Restore" on A1.**
  Billing is phase 8; a badge that gates nothing and a restore link with nothing to
  restore are both stubs. Spotify's card says "PREMIUM" because that is a Spotify
  requirement, not an ActiPlayer one.
- **"Run setup again" added to the Home settings sheet.** With the flag migration, an
  existing install skips the wizard entirely — without this there is no way to see it
  again short of clearing app data and destroying the pools.
- **`GuideScreen` deleted.** The wizard covers what it explained, and it was already
  unreachable after phase 3 moved the Home header.

### Fixed during on-device testing (phases 4 + 5)

- **The recap's stat row scrolled horizontally and put OVERRIDES off-screen** — the one
  stat the recap exists for was the one hidden, with no scroll affordance. Now a 2x2 grid
  with all four visible.
- **Long track titles clipped into the play count** instead of ellipsising. `maxLines = 1`
  without `overflow` truncates by clipping, which reads as a layout bug rather than a long
  title. Same class of defect as the phase-3 signal chips.
- **A2's activity cards did not match heights** when a name wrapped to two lines. The row
  now measures at `IntrinsicSize.Min`.

## 2026-09-10 — Visual pass: cover art, launcher icon, activity glyphs

Not a numbered phase. The app read as lifeless, and an audit found the causes were mostly
missing content rather than missing decoration.

- **The app had no launcher icon at all.** `res/` held only `font` and `values` — no
  mipmap, no drawable, and the manifest declared no `android:icon`, so it shipped as the
  default Android robot. Now an adaptive icon (background + foreground + monochrome for
  themed icons), drawn as the same mode strip the A1 welcome screen uses: the app's one
  idea, in its own three colours. Vector only, no bitmaps, and everything inside the
  66-unit safe zone a launcher mask guarantees. Plan §10 lists "adaptive icon" as release
  polish, but having *none* is a different thing from having an unpolished one.
- **No cover art was loaded anywhere, and roughly half the library has it.** A survey of
  the test device found embedded APIC frames in 73 of 161 tracks. The
  `MediaStore.Audio.Albums.ALBUM_ART` column reads NULL for every album on a modern
  device — it has been dead for years — so `AlbumArtLoader` uses
  `ContentResolver.loadThumbnail` on the track URI (API 29, our minSdk).
- **No image library.** One MediaStore source at one size means an `LruCache` and a
  coroutine are the whole job; Coil would be a dependency that also has to clear our AGP
  8.13 cap. Misses are remembered so a track with no art is not re-probed on every scroll.
- **`ArtTile` is the fallback, never an empty square.** It generates a gradient in the
  track's energy colour. This also fixes a regression from phase 3: rebuilding the session
  screen replaced phase 1's `ArtTile` with a plain grey `Box`, which was the most visible
  dead space in the app.
- **The loader is a composition local, not a parameter.** It is a cross-cutting UI service
  with no per-screen configuration — the same mechanism image libraries use — and
  threading it through six screens would have been noise.
- **Schema v4 adds `trackMediaId` to session events** so recaps can show covers too. Pure
  column addition, so Room generated and validated the migration; fourth auto-migration in
  a row with no hand-written DDL.
- **Activity glyphs go inside shapes that already exist** — the coloured ring on B1, the
  card on A2, the mode-coloured circle on History rows. Plan §5 keeps mode colour as the
  only saturated channel, so identity had to come from the shape already on screen rather
  than from adding another element. On A2 the glyph replaces the radio circle: the orange
  card fill already states selection unmistakably, so the radio was carrying no
  information the fill did not.

Deliberately not done: the recap still draws an empty timeline band when a session
recorded no heart rate or speed. A "no heart rate recorded" note would be a small
improvement and is still open.

## 2026-09-10 — Bug: a tab could open another tab's screen

Reported on device as "sometimes the Home button doesn't work properly". Reproduced:

1. Home → tap the Music ready-check row (a plain `navigate`, not a tab switch)
2. Tap the History tab
3. Tap the **Home** tab → lands on **Music**

- **Cause: `saveState`/`restoreState` on a flat route graph.** Those options exist for tab
  bars whose routes live in *nested* graphs, where each tab's stack is saved under its own
  graph. These routes are flat siblings, so `popUpTo(startDestination) { saveState = true }`
  saved whatever sub-screen happened to be above the start destination *under the start
  destination itself*, and the next `restoreState = true` navigation to Home put it back.
  It only misfires after reaching a screen by a non-tab route — the ready-check rows, the
  mini player, the last-session card — which is exactly why it was intermittent.
- **Fix: drop both options.** A tab now always opens its own root, which is what a tab bar
  promises. The alternative — restructuring into three nested graphs so save/restore works
  as designed — buys per-tab scroll restoration that nothing in the plan asks for, at the
  cost of a graph rewrite.
- **Cross-tab links now use the same helper.** Home's Music ready-check row went through a
  bare `navigate`, which is what produced the mismatched stack in the first place; it now
  behaves exactly like tapping the Music tab.

Verified on device across every route: tab switching, sub-screens (C2/C4/recap/streaming),
and all the session paths — start, back to the shell, mini player in, back out, end.

## 2026-09-10 — Phase 6: Running, Generic, timer profiles, sensitivity

Plan §6.6. Every activity in plan §1 now has a working profile, so screen A2 shows the
full 2×3 it was drawn with.

### The clock profiles (Strength, HIIT)

- **`TimerEngine` is the only engine with nothing to tune.** Indoors there is no GPS and
  no altitude, and lifting barely moves the phone, so no amount of signal processing would
  beat simply knowing the plan. That also makes it the one profile that cannot be subtly
  wrong the way a threshold can.
- **Still pure.** Pausing and skipping are bookkeeping against *frame* time, not calls to
  a clock, so the "time only arrives on frames" rule survives. Resume takes effect at the
  next frame boundary — at 1 Hz that is at most a second, and it keeps the engine
  deterministic under test.
- **EMOM is modelled as work 60 s / rest 0 / 12 rounds, and the tap is what creates the
  rest.** "Every minute on the minute" means the recovery *is* whatever is left once the
  reps are done, which is exactly what ending the interval early produces. A fabricated
  split like 40/20 would have been inventing numbers the athlete did not ask for.
- **Tap-anywhere-to-end-early keeps a 4 s undo, not the plan's 2 s.** Two seconds to
  notice an accidental pocket tap and find Undo mid-set is not enough; the cost of the
  longer window is nothing.
- **B4 replaces CHILL/AUTO/HYPE with PAUSE and SKIP.** Overriding a clock means
  controlling the clock, not nudging an energy band — and PAUSE stops the music too, which
  is why `MusicSource` gained `pause()`/`resume()`.

### Running and Generic

- **Cadence is a new `FeatureFrame` field**, counted from accelerometer peaks over a 10 s
  window (peak above 1.5 m/s², 250 ms minimum spacing, which caps the reading at a
  plausible 240 spm). A gait is far cleaner in this signal than speed is at running pace.
- **Where speed and cadence disagree, cadence wins.** It measures the athlete rather than
  the fix, so a walking gait is a walk however fast a drifting GPS claims you are moving —
  and the whole profile keeps working on a treadmill, under trees, or in a tunnel with no
  fix at all.
- **Running keeps the HR-modulates-only rule** — a hard pulse can promote a
  merely-running pace to PUSH, but only alongside movement evidence. Generic is the
  strictest of all: motion alone decides, and there is a test that HR at 95% of max cannot
  move it off REST.

### Sensitivity

- **One `downSwitchScale` on `EngineConfig`, applied through `config.down(ms)`.** Quick
  0.6 / Balanced 1.0 / Sure 1.5. Up-switch windows are never scaled and there is a test
  that walks all three settings to prove it: the asymmetry *is* the design, and a drop-in
  has to land instantly whatever the athlete chose.

24 new engine tests (Timer 9, Running 8, Generic 5, sensitivity 2), 97 in total.

## 2026-09-11 — Phase 6 on-device test, and what it caught

Phase 6 compiled and its unit tests passed a day before any of it was run on a phone.
Five defects survived that, all of them invisible to the compiler.

**The activity gate was duplicated.** `readyActivities` existed twice — once in the
onboarding step (A2) and once in the Home sheet. Phase 6 updated only A2, so Running,
Strength, HIIT and Generic rendered as "· soon" and were unselectable from the main
screen. The whole phase was unreachable from the place people actually start a session.
Both copies are now gone: every activity has a profile, so nothing needs gating. The
lesson is the ordinary one about duplicated truth, but the specific shape is worth
naming — a feature flag that lives in two files fails silently in exactly one of them.

**The activity chips scrolled horizontally.** Seven chips in a scrolled `Row` meant that
opening the picker with Strength selected showed Snowboarding, Cycling and Walking, none
of them marked. The row is a `FlowRow` now and wraps to three lines; all seven are
visible without scrolling. A picker that hides the current selection is worse than a
taller sheet.

**D4 read "Start · 0 min" for any plan under a minute.** `spokenMinutes` floored. Custom
plans can legitimately be 30 s, and "0 min" reads like a bug rather than a duration, so
sub-minute totals are now spoken in seconds.

**B4 kept PAUSE and SKIP alive after the plan finished.** Neither does anything once the
clock is done — `endIntervalEarly` returns early on a finished plan, and pausing a
stopped clock is meaningless. The finished state now shows the ordinary CHILL / AUTO /
HYPE row instead, which hands the cooldown back to the athlete. Overrides are resolved
in `SessionCoordinator.bandFor()` above the engine, so this needed no engine change.

**Copy assumed a board.** The end-session sheet offered "Keep riding" during a strength
session, and B4's hint said "tap anywhere to end the set early" while resting. Now "Keep
going", and the noun follows the interval and the activity.

Confirmed working on the Galaxy A52s: D4's four presets and all three steppers; B4's
countdown, round header, per-activity SET/WORK label, and the work→rest→work→DONE
progression with the queue swapping bands on each flip; PAUSE holding both the clock
(0:54 for 8 s) and the media session (position 12113, unchanged); SKIP SET; tap-to-end
with Undo restoring the interval (SET 0:52 → REST 0:29 → SET 0:47); sensitivity writing
through to `EngineConfig.downSwitchScale` at session start; Running and Generic routing
to B2 rather than D4; and the recap recording a timer session with its WORK/REST split.

One honest distortion left in place: time spent sitting on the DONE screen is recorded
as REST, because it is. A 30 s plan left idle for a minute recaps as mostly rest.

## 2026-09-29 — Full review: logic, consistency, UI/UX

Not a phase. A read of every module against the plan and the design, then every screen on
the Galaxy A52s (font scale 1.1, 3-button navigation, dark mode). Ordered by how badly
each finding would have hurt a real session.

### Critical: sessions did not survive the pocket

- **The playback service never became a foreground service.** `dumpsys` showed
  `startForegroundCount=0` on every session. Media3 only learns about a `MediaSession`
  when a `MediaController` connects to it, and nothing in this app ever connects one — so
  the session was never registered with its own service: no media notification, no
  foreground promotion. On Android 9+ a background app gets no accelerometer events,
  background GPS is throttled, and the process is fair game for the low-memory killer. In
  other words the pocket, which is the product, did not work; every test so far had the
  phone on a charger with the screen held on, which hid it.
  Fix: `addSession()` in `PlaybackService.onCreate`; `onUpdateNotificationAsync` forces
  the foreground for the whole session (paused too — the sensors are the product, not
  just the music); and the location type is added to Media3's media-playback type while
  the app is still visible, because Android grants while-in-use location to a foreground
  service only if it asked while visible. Verified: `isForeground=true types=0x0A`
  (media playback | location), the accelerometer connection active and not idle with the
  screen off, GPS still requested at 1 s, and recorder samples arriving every 5 s through
  a minute of screen-off.

### Engine and sensors

- **Cadence never reached the running engine.** The pipeline trimmed accelerometer
  samples to 5 s; cadence asked for at least 5 s of a 10 s window, so it returned null on
  nearly every frame. The running profile's cadence path — PUSH on gait, WALK on gait,
  the treadmill case — has never run on a phone; the engine tests fed synthetic cadence,
  so they could not see it. The peak counter moved to a pure `Cadence` object with its own
  tests (including one that fails at 5 s retention), and the buffer keeps 11 s.
  **Unvalidated and worth watching:** peak counting on a pocketed phone may count a
  secondary bump per step at walking pace. If a real walk shows PUSH on the running
  profile, that is why; the hardware step detector (needs the ACTIVITY_RECOGNITION
  permission) is the robust alternative, and a product decision.
- **Cycling EFFORT almost never confirmed with a heart-rate strap.** The rule demanded a
  speed gain (over within ~6 s) and a rising 60 s HR trend in the *same* three frames.
  HR lags effort by 10–30 s and the trend lags HR, so after a stop at a light the trend is
  still negative when the rider is back up to speed. Now a gain opens a 45 s window
  (`cyclingEffortHrGraceMs`) for the pulse to agree. The test that proves it models HR
  with a first-order lag and the pipeline's own trend estimate; it fails on the old
  engine and passes on the new.
- **Two up-switches took the down-switch window.** Running WALK -> CRUISE and generic
  REST -> EASY are rises in energy but confirmed in 15 s (scaled by sensitivity), because
  the window was chosen by target state alone and those middle states are reached from
  both sides. Now direction-aware: `runningCruiseUpConfirmMs` / `genericEasyUpConfirmMs`
  (3 s, unscaled) going up, the old windows coming down. The snowboard engine's explicit
  per-state windows are deliberate (a stopped chairlift must not flip LIFT to BREAK) and
  are unchanged.
- **HIIT's work block is band 5, not 4–5** (plan §3.3): `hiitWorkEnergy`.
- **The clock claimed a sensor.** `Evidence.ON_THE_CLOCK` was tagged MOTION, so timer
  decisions reported MOTION as contributing. Evidence's signal is now nullable.

### Playback

- **A small pool drifted out of its band.** `TrackPicker` dropped the band before it
  dropped the no-repeat rule, so with six Hype tracks and ten remembered, a riding block
  was fed Chill after six songs (seen on device in phase 6 and wrongly put down to "the
  thin-pool fallback"). Now: in band and fresh -> widened ±1 -> repeat the one heard
  longest ago -> only if nothing within ±1 exists, the nearest band (an empty Mid lends
  BREAK the Chill pool, not Hype). Same rule for the Spotify batch.
- **A pause did not stick.** Pausing from the notification or a headset lasted until the
  next mode change, which started the music again; a phone call ending resumed music the
  athlete had paused; permanent focus loss (another app playing) was undone at the next
  switch. The source now owns "paused": while paused, mode changes and skips cue the
  right track silently. A transient loss resumes only what it interrupted.
- **Controllers go through the source.** The MediaSession wraps a `ForwardingPlayer` per
  ExoPlayer that routes play / pause / next to `LocalSource`. ExoPlayer with one item
  reports no "next", so the notification had no skip and a headset double-press did
  nothing; next is advertised now and means skip. `ForwardingPlayer` is `@UnstableApi` in
  Media3 1.11 — opted into deliberately, it is the documented way to do this.
- **Track ends crossfade.** Tracks used to play to `STATE_ENDED` and the next one faded
  *in* from silence — not a crossfade. The next track now starts crossfade-length before
  the end.
- **The notification shows the mode** (plan §1): new tracks carry the mode label as
  metadata subtitle, and a `DefaultMediaNotificationProvider` subclass shows
  "artist · mode". Status-bar icon is the app's mark, not Media3's note.
- **Pause when headphones unplug** is a setting now (default on), per D3.

### Data and session

- **`<unknown>` reached every screen.** MediaStore reports a missing artist as the literal
  `<unknown>`. `TrackNames` drops it, and when the artist tag is missing splits
  "Artist - Title" filenames (only on a spaced dash, so "Jay-Z" survives). Tested.
- **A double tap on Start launched two sessions** — two recordings, and a frame collector
  that outlived the session and fed every later one twice. `startSession` is guarded, and
  exception-safe so a failed start cannot lock Start out.
- **One pause path.** B4's PAUSE, the notification and a headset all pause the backend;
  the timer clock follows the backend, so a notification pause no longer leaves the set
  running without its music.
- **Timer plans are per activity.** One shared slot meant setting up a Tabata for HIIT
  replaced the Strength 30/90. The Strength plan keeps the old key.
- **Mode names and tiers have one home** (`ModeNames.kt` in `:session`): a running
  CRUISE is mid energy and now reads slate, not the ice blue of walking; Strength's WORK
  reads SET in the recap and notification too.

### UI and UX

- **Bottom sheets turned the status bar icons invisible.** Material derives a sheet's
  content colour from its container; on our `surface` that resolved to the dim grey,
  which dimmed every line of sheet text and — because the sheet picks light or dark
  system-bar icons from that colour — put dark icons on a dark screen and a white
  navigation bar under the sheet. One `ActiBottomSheet` now sets both explicitly.
- **Edge to edge on every version**, with dark-style bars: B2's mode colour now reaches
  under the status bar as drawn, with dark icons for the session's length.
- **D3 Settings, as drawn**, replacing a sheet of mismatched cards (max HR lived in a
  different sheet). Adds units (km/h or mph; plan §1 said the setting existed — it did
  not) and pause-on-unplug. The Account group waits for phases 8 and 10.
- **One activity vocabulary.** Four naming functions had produced "Hiit" on Home,
  "GENERIC" on the session header and "Snowboarding" beside the wizard's
  "Ski / Snowboard". `ActivityGlyph.kt` holds the one list.
- **Durations say their unit**: "48s", "2m 46s", "3h 12m". "2:46" had meant two minutes
  on one History row and two hours on the next, and a 48 s session recapped as
  "0m of intervals".
- **The session screen** gains pause/play beside skip (headphones dropping paused the
  music with no in-app way back), a PAUSED line, a two-line title, haptics on every
  in-session tap, radio semantics on the override row, and labels centred in their boxes
  (top-padding centring pushed them out as the font scale grew). The End pill has dark
  text (white on ice blue was the least legible thing on screen); the fallback line no
  longer wears Spotify green.
- **B4** matches the design: 96 sp countdown, the plan in the header on its own line
  (one line ellipsised away "OF 8"), "Next: REST 1:30", tap *anywhere* above the card to
  end early, and one PAUSE rather than two.
- **Home**: the hard-coded "MK" avatar (the owner's initials, for every user) is a
  settings mark; the whole activity card changes activity (not a 28 dp word); the Music
  row warns in `hot` when a pool is empty or under 8 and says what that does to the
  music; the heart-rate row says "none paired" instead of "connects at start" when
  nothing is; the Sensors row tells the truth when location is off and links to fix it
  (it was always green); the last-session card has the design's mode strip.
- **Heart-rate setup** is one component for A4 and the Home/Settings sheet, restyled to
  the design. Bluetooth is never switched on unasked. The old flow asked the system to
  enable Bluetooth and started scanning the instant the request returned, while the
  radio was still turning on: the scan silently failed and A4 sat on "Not scanning. Check
  Bluetooth is on." with Bluetooth on and no way forward. `HeartRateMonitor` now tracks
  the radio and runs a requested scan once it is actually on. The sheet's claim that
  heart rate "never changes the mode" was false for three profiles and is gone.
- **History** rows show start time, colour by the mode the session mostly spent in (not
  its first segment), and have even heights. **Recaps** order mode tiles hype-first, show
  only stats that exist (no speed for clock sessions, no "— bpm"), say when nothing was
  recorded, and can **delete** the session.
- **Music**: pool chips wrap instead of middle-ellipsising "Music/S…sung", and say what a
  source contributes — "3 of 161" — where "161" beside a pool of 3 looked like broken
  counting. C2's empty checkboxes have an edge, and "Nothing selected" reads "No changes"
  when the pool's own sources are checked. Streaming has a way back.
- Steppers are one component, 48 dp (56 dp on D4), and **repeat when held**.

### Found, deliberately left

- **A cycling climb is CRUISE.** At 10 km/h with HR 160 the speed-only rules call it a
  cruise and play chill — a climb is the hardest effort on a bike. Fixing it means
  climb detection (vertRate) or letting HR alone promote, both product calls.
- **A3 promises "falls back to local"** for Spotify. True from phase 7, not today.
- **targetSdk 35** is below Play's API 36 requirement (since 2026-08-31); phase 10.
- **A 48 s HIIT test session from the phase-6 device test was deleted** while clearing
  this review's own test sessions — a scripted tap hit the row below the one intended.
  It was test data from that phase, not the owner's.

## 2026-09-29 — Typography: Inter, sentence case, no monospace

Requested by the product owner: the app "looks AI generated".

- **The type system came from the generated design (plan §5), and it showed.** Instrument
  Sans for the UI, and JetBrains Mono — a coding typeface — for every label, caption,
  number and status line, set in capitals with wide letter-spacing ("READY CHECK",
  "CONNECTS AT START · MAX 190", "POCKET THE PHONE · WE HANDLE THE REST"). Spaced-out
  monospace capitals are the signature of a generated mock-up, and a music app has no
  reason to speak in a terminal's voice. This is a deliberate deviation from plan §5 and
  from the `design/*.png` references, at the owner's direction.
- **Inter, from one variable file** (Inter 4.1, SIL OFL; licence bundled at
  `assets/licenses/Inter-OFL.txt` — the previous fonts shipped without theirs, which the
  OFL requires). One 880 KB file replaces seven static TTFs and carries every weight
  plus the optical-size axis: text sizes use Inter's text cut, big type (mode names, the
  96 sp countdown, screen titles, stat numbers) its display cut, which is drawn tighter
  and finer for large sizes.
- **Tabular figures instead of a monospace.** The mono was standing in for digits that
  do not shift; Inter's `tnum` does that job, applied where numbers tick or line up
  (countdown, elapsed clock, stats, durations, counts, steppers). Prose keeps proportional
  figures.
- **Sentence case everywhere except the mode names.** Section labels are small semibold
  text in the muted colour; captions are plain 13 sp; buttons read "Chill · Auto · Hype",
  "Pause", "Skip set". The big mode name (RIDING, SET) stays in capitals — it is read at
  arm's length mid-run — and so do acronyms. Copy was rewritten where capitals had been
  hiding telegraphese: "EFFORT → HYPE · CRUISE → CHILL" became "Pushing is Hype,
  cruising is Chill"; the pools say what they feed ("Riding, efforts, sprints and sets").
- The one monospace left is the platform's own, for the raw telemetry behind the "Show
  live signals" developer toggle, where a monospace is the honest choice.
