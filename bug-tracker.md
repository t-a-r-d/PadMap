# PadMap — Bug Tracker

Each entry covers one bug: what it is, every fix attempt and its outcome, and what hasn't been tried yet.
CC reads this before investigating any bug.

---

## BUG-001 — Wireless debugging “connected” toast loops (Oppo A40)

**Status:** v61 dump: inject init ok; process dies on ADB detach.
**Reported:** 2026-08-18 on Oppo A40 / ColorOS, PadMap v54
**Reported:** 2026-08-18 on Oppo A40 / ColorOS, PadMap v54

After pairing, PadMap reaches wireless ADB, then Android’s “Wireless debugging connected” toast keeps sliding down. Looks like connect → drop → connect.

### Cause

Home `ON_RESUME` calls `SidecarHost.ensureRunning`. `connect()` shows ColorOS’s toast, which pauses/resumes PadMap. v54 has no in-progress lock, so the next resume starts another `start()` → `connectAny()` `disconnect()`s the first session and `connect()`s again. Sidecar never stays up, ping keeps failing, toast loop.

### Attempts

- [in progress] One auto-connect per process from Home resume. Overlapping `ensureRunning` returns immediately. Drop ADB after the sidecar pings (sidecar is localhost; holding wireless ADB retriggers ColorOS). Settings **START** still force-retries. Not on an APK until Damien says to build.
- [in progress] 2026-08-18 Home showed `Sidecar did not start ECONNREFUSED` then the text vanished. Same loop: next resume overwrites `SidecarHost.status`. Also the start command is `sh -c 'app_process &'` over an ADB shell stream — closing that stream SIGHUPs the child, so nothing is left on port 18741. Next APK: `setsid`/`nohup` so the sidecar survives ADB disconnect, poll ping, keep the failure on the Home card.
- [failed] v56 on A40: copy succeeded, then `Sidecar did not start` / connect to 18741 from 40744 refused / never stayed up. Empty sidecar log. `setsid`/`nohup` one-liners did not leave a process. 40744 is PadMap’s ephemeral source port, not ADB. Next: `--nice-name` after the directory makes `app_process` treat it as the class; `nohup CLASSPATH=...` treats the env assign as the executable. Start via a script (`--nice-name` before the dir), keep launch output, dump jar/ps/log on failure.
- [failed] v58 on A40: jar 6861 ok, script 335 bytes +x, app_process64 present. PID empty, log 0 bytes, ps is only the PadMap app. All three launch cmds returned no output. Later `>` launches wiped any first-attempt log; last cmd had no nohup so ADB close would SIGHUP it. Next: drop `--nice-name`, set ANDROID_DATA/ANDROID_ROOT and `-Djava.class.path`, run a foreground `check` once to keep ART’s error, then one nohup start (do not truncate), include probe + logcat in the share dump.
- [failed] v60: START toast “log is under START” but the text is only `Starting injector…`. `ensureRunning` returns immediately while `inProgress` (Home resume or the 6s check) and ignores `force`. Diagnose never runs; `bestInjectorLog` then picks the progress line. Next: if force, wait for the in-flight start; always diagnose after a failed launch even if ADB throws; do not treat progress strings as the dump.
- [ok] v61 check: `CHECK_EXIT:0` inject via `InputManagerGlobal.injectInputEvent`, displays=[0] touchDev=4. Init path is good.
- [failed] v61 listen: `DETACH: FORK:13945` then no pid, log 0 bytes, nothing on 18741. Check works in the ADB foreground; nohup child dies when that stream closes (ColorOS SIGHUP). Next: daemonize inside the start script (`trap HUP` + `setsid $0 --fg`) so ADB only waits for a one-line `DAEMON:pid`.

### Not tried

- Device confirmation that the toast loop is gone on v56+ (not reported).

---

## BUG-002 — Injector jar copy truncated on Oppo A40 (v55)

**Status:** v56 copy reached start (size check passed). Sidecar still died — BUG-001.
**Reported:** 2026-08-18 on Oppo A40, PadMap v55

Home: `Injector copy failed (phone has 6656 bytes, expected 6861)`.

### Cause

`shell:dd of=file` uses a 512-byte block. 13 × 512 = 6656; the last 205 bytes never landed. Closing the ADB stream is not a clean EOF, so toybox `dd` drops the partial last block.

### Attempts

- [in progress] Push with `dd bs=<exact size> count=1` so it stops after the full jar. If the size still mismatches, write base64 over the shell and `base64 -d` on the phone. Not on an APK until Damien says to build.
- [ok] v56 on A40: no “phone has 6656 bytes” — start reached the sidecar listen step. Copy fix held. Remaining failure is BUG-001.

---

## BUG-003 — Overlay button stays on Android home after leaving PadMap

**Status:** fix written, not on device yet
**Reported:** 2026-08-18, PadMap v55 on Oppo A40

Closing PadMap (Android Home / leaving the activity) leaves the floating config button on the launcher.

### Cause

Home `ON_RESUME` forces the icon visible. ColorOS often does not fire a launcher `WINDOW_STATE_CHANGED` after PadMap stops, so `repositionForGame()` never hides it.

### Attempts

- [in progress] Hide the floating icon in `MainActivity.onStop`. Game window events still show it in a game. Known ColorOS launcher packages treated as system UI. Not on an APK until Damien says to build.

### Not tried

- Device confirmation on A40 after the next APK.

---

## BUG-004 — Injector running, sticks/buttons do nothing in game (v62)

**Status:** in progress
**Reported:** 2026-08-18 on Oppo A40, PadMap v62

Sidecar stays up (`Injector running`) but gamepad sticks and buttons do not touch the game.

### Cause

Not confirmed on device. Code paths that drop playback:

- Key filter and joystick `motionEventSources` are only enabled if the **active** layout already has Tap / Drag mappings. Opening a game with no matching `packageName` creates a **new empty** layout and makes it active, so both flags go off and a11y never sees the pad.
- `InputManagerGlobal.injectInputEvent` resolved in check; inject may still be dropped (shell should use `IInputManager`).

### Attempts

- [in progress] Always request key filter + joystick/gamepad motion. If the active layout already has mappings, bind it to the game instead of replacing it with an empty one. Prefer `IInputManager` for inject. Clear `padMapUiVisible` on activity stop.
- [in progress] v64 still no touches. Overlay DEBUG + SHARE dumps injector ping, active layout/mappings, uiVisible, keyFilter, motionSources, and a live event log (why each press was skipped or injected).
- [in progress] ColorOS may never send pad events to AccessibilityService. Add a full-screen `FLAG_NOT_TOUCHABLE` focusable overlay on games that receives keys/motion and feeds playback. Touches still go to the game.

### Not tried

- Device confirmation after the next APK.

---

## BUG-005 — Menu icon goes off the top after landscape overlay / portrait

**Status:** in progress
**Reported:** 2026-08-18

Floating config button disappears off the top when overlay size was set in landscape and the game (or phone) is portrait. Icon must sit at the current screen’s top-centre, not overlay W/H/X/Y. After deleting a game layout in PadMap, the next time that game opens the button should start at top-centre.

### Attempts

- [in progress] Pin the icon with `Gravity.TOP|CENTER_HORIZONTAL` and live window metrics. Never use saved overlay dimensions for the icon. Reset to that pin when a game layout is deleted.

### Not tried

- Device confirmation.

---

## BUG-006 — Swiping PadMap from Recents leaves overlay/a11y running

**Status:** in progress
**Reported:** 2026-08-18

EXIT on Home calls `disableAndStop()` then `finishAndRemoveTask()`. Swiping the task away only kills the activity; the accessibility service and overlay stay up.

### Attempts

- [in progress] `onTaskRemoved` and `onDestroy` (when finishing) call the same `disableAndStop()` as EXIT.

### Not tried

- Device confirmation.

---

## BUG-007 — Auto layouts and menu button appear on non-games

**Status:** in progress
**Reported:** 2026-08-18

PadMap created presets and showed the overlay button on apps that are not games (CATEGORY_UNDEFINED). Menu button must not show on the Android launcher or non-game apps. Automatic layouts only for packages marked as games.

### Attempts

- [in progress] Use `GameScanner.isInstalledGame` (CATEGORY_GAME / FLAG_IS_GAME, not system) for auto-layout and icon visibility. Hide the button on PadMap Home and any non-game package.

### Not tried

- Device confirmation.

## How to use this file

When a bug is reported:
1. CC reads this file first — never attempts a fix that's already been tried
2. CC adds an entry before writing any code
3. After each attempt (pass or fail), CC updates the entry and pushes to GitHub
4. Root cause theories are listed as checkboxes and worked through in order
