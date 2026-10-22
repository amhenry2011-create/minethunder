---
name: minethunder-context
description: Use for EVERY MineThunder task in /Users/henry/code/minethunder, including radar, TWS, ACM, lock, Notch/39, seeker, HUD, missiles, commands, build, or jar install. Read this context plus LOCK_LOGIC.md before changing anything.
---

# MineThunder context

## First actions

1. Read `/Users/henry/code/minethunder/LOCK_LOGIC.md` for the original lock/TWS/ACM/seeker behavior from `demo/radar-demo.html`.
2. Read `/Users/henry/code/minethunder/TWS.md` for the current implemented behavior.
3. Read `/Users/henry/code/minethunder/HANDOFF.md`, `MISSILE_LOCK_LOGIC.md`, `SEEKER_HUD.md`, `HEAT_SOURCES.md`, and `OCCLUSION.md` for latest state, lock logic, seeker drawing, IR heat, and LOS rules.
4. Inspect the current source before editing; do not rely on old assumptions.

## Project facts

- Project root: `/Users/henry/code/minethunder`
- Minecraft: 1.21.1, NeoForge 21.1.236, package `com.minethunder`
- Build: `./gradlew build --no-daemon --console=plain` (NeoForge/ModDevGradle; see `MIGRATION_NEOFORGE.md`)
- If Gradle hangs at startup: retry once, then `./gradlew --stop`.
- Jar: `build/libs/minethunder-0.1.0.jar`
- Installed mods dir: `/Users/henry/Desktop/minecraft/versions/1.21.1-NeoForge_21.1.236/mods/`

## User workflow

- Default: edit, build, copy jar; do NOT launch the game unless the user explicitly asks.
- When the user requests a visual style, ask where it should be drawn (radar panel vs main screen) and whether it is fixed or follows the target, unless already specified.
- Current HUD rule: the manual four-corner acquisition bracket is on the **main screen**, not the radar panel. Panel uses horizontal target line + side vertical selection lines; established TRK PD is a bright green dot.
- User verifies in-game.
- Keep HUD style close to the Demo; user is sensitive to Demo fidelity.
- No unsolicited extra features.

## Original Demo lock logic (summary)

- `inScanVolume`: radar on, range gate, `abs(az - scanAz) <= effectiveScanHalf`, `abs(el - scanEl) <= 20`.
- Notch/39: for **every mode except SRC**, targets with `abs(closure) < 20` are not visible. TWS/ACM are not exceptions.
- `contactVisible`: scan volume + `lastSeen < CONTACT_MEMORY` (Demo 12s; Mod currently 5s).
- Scan paint: hit only when `abs(az - sweepAz) < 6` and `abs(el - rowEl) < 4`.
- Non-TWS lock: `attemptLock` uses the panel strobe/nearest visible target within 30px; ACM requires inside ACM box.
- TWS: one `twsTrack` (soft track), auto scan center follows predicted track, `Alt+T` cycles, `Alt+F` locks/unlocks. Locked TWS can carry lock when switching.
- ACM: Demo automatically locks after 1/2/3 paint hits based on range, inside fixed ACM box/12km; manual lock disabled. Current Mod keeps ACM auto-intercept, with fixed position/width and adjustable range.
- Lock states: acquiring progress -> tracking. Square target box for tracking; corner bracket for acquiring.
- Seeker rings: radar lock layer plus square target box; SARH single-white-searching blocks launch.

## Current Mod state (source of truth may change)

- Loader migrated to NeoForge 21.1.x; see `MIGRATION_NEOFORGE.md`.
- Radar modes: SRC, SRC PD, TWS, GMI, SEA, ACM, STT. MTI removed. Air modes accept AIR only; GMI accepts GROUND+SEA moving; SEA accepts SEA ships only. See `RADAR_MODES.md`.
- Firework crossbow canceled. Three missile fireworks are inventory ammo; `Ctrl+Alt` fires directly from inventory (no arm step). Auto ammo priority: manual → ARH → SARH → IR. Recipes exist for IR/SARH/ARH. Guided firework rendering remains.
- Radar ranges 40/100/300/600/1000 b, default 300; scan patterns 40x20/120x10/120x20, default 120x20; 240x10 removed.
- Target selection is panel-only: Alt+T changes panel selection, no lock/screen action; Alt+F starts TRK PD. TRK PD is single-target only.
- IR lock is implemented: the small ring acquires for 1 second, then follows the target inside the 6x visual outer ring; the outer ring is authoritative at about 73.9° effective off-boresight.
- Radar panel selected target has horizontal + two vertical lines. Long screen vertical line removed. TWS scan center follows selected target.
- Seeker toggle hides/shows rings only; it must not clear radar lock. Center crosshair removed; radar lock layer has progress arcs.
- Current guidance core uses SI->blocks/tick conversion and PN; ARH active-seeker sophistication and full SARH/IR behavior are still in progress.
- Launch line/inertial circle rendering has started; verify in game.

- TWS: multiple echoes retained, only one current tracked/hard-lockable target; `Alt+T` switches current; `Alt+Q`/`Alt+D` adjust TWS/global range/scan; wide ±60 scan allows ±30 center shift.
- ACM: independent range, default 100 b; `Alt+Q` adjusts range; `Alt+D` is disabled and width stays ±3°; scan position fixed.
- Notch: realistic degradation via `RadarCore`: threshold 5 m/s, burn-through 0–50 b, mode sensitivity SRC 0 / MTI 0.7 / ACM 0.85 / PD/TWS/STT 1.0. No hard invisibility; no hard server rejection.
- Lock flow: `Alt+F` starts a fixed 4-corner acquisition at the approximate scanned position (20 ticks), then switches actual `RadarMode.STT` (`TRK PD`); panel target becomes bright green dot; main screen green square + speed data; unlock/drop restores previous search mode.
- Automatic lock frames are removed except TWS current marking and ACM auto-intercept. ACM auto goes directly to TRK PD.
- Track loss: dim over 100 ticks (5s), inertial tracking, reacquire restores; >5s drops lock.
- ACM: position fixed; `Alt+D` disabled (width fixed ±3°); `Alt+Q` adjusts range; default 100 b.
- Radar missile: no outer gimbal ring; seeker lock ring only when `Alt+X` enabled and radar locked. IR: outer ring is the 6x visual `lockAngleMax` boundary (~73.9° effective) and may clip off-screen; lock ring only when IR seeker active.
- Seeker keys: `Alt+X` toggles radar seeker; `Ctrl+Alt` fires directly when launch conditions are met.
- GMI/GMTI has no independent mode implementation; do not claim it exists.

## Key files

- `src/main/java/com/minethunder/client/RadarHud.java`
- `.../client/RadarHudClientTicker.java`
- `.../client/RadarHudRenderer.java`
- `.../client/KeyInputHandler.java`
- `.../client/RadarEcho.java`
- `.../client/TwsTrack.java`, `TwsTrackManager.java`
- `.../radar/RadarCore.java`, `RadarMode.java`, `SeekerLockModel.java`
- `.../missile/MissileEvents.java`
- `.../network/RadarSyncPacket.java`, `.../radar/ServerRadarState.java`
- `demo/radar-demo.html` (original reference; read, do not copy blindly)

## Reporting

After a task: report files changed, build result, jar copied, commit hash, push status, and what the user should test. Do not launch the game unless asked.

After every completed version change, follow `.opencode/skills/minethunder-auto-release/SKILL.md`: build, copy the jar, commit, and push to `origin main` automatically.
