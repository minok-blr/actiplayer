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
