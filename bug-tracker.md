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

v65 overlay DEBUG on Shadowborn (`com.onemb.shadowborn`): play catcher focused, layout mapped (L-Stick,A,X,X,Y,B), `uiVisible=false`, then every playback attempt is `sidecar off (NetworkOnMainThreadException)`. `SidecarClient.ping()` / `ensureConnected` opens a TCP socket on the accessibility / overlay main thread. Android throws `NetworkOnMainThreadException`, so inject never starts even when the sidecar is up. Settings can still show “Injector running” because that path pings off the main thread.

### Attempts

- [in progress] Always request key filter + joystick/gamepad motion. If the active layout already has mappings, bind it to the game instead of replacing it with an empty one. Prefer `IInputManager` for inject. Clear `padMapUiVisible` on activity stop.
- [in progress] v64 still no touches. Overlay DEBUG + SHARE dumps injector ping, active layout/mappings, uiVisible, keyFilter, motionSources, and a live event log (why each press was skipped or injected).
- [in progress] ColorOS may never send pad events to AccessibilityService. Add a full-screen `FLAG_NOT_TOUCHABLE` focusable overlay on games that receives keys/motion and feeds playback. Touches still go to the game.
- [in progress] v65 dump confirmed NOTME on ping. Move all `SidecarClient` socket I/O onto a dedicated thread so playback ping/inject from the main thread no longer throw.
- [ok] v66 Shadowborn: `ping=true` `down ok=true`. Inject reaches the game. Stick only nudged; four buttons at once kept tapping one button.
- [in progress] v66 dump: same pointer ids reused (`B`/`X`/`Y`/`stick` on pid=0/2), no `btn up`, play catcher torn down mid-game (`play catcher focused=true` twice). `TYPE_WINDOWS_CHANGED` treats a focused System UI window as leaving the game and destroys the catcher — key-ups never arrive, HOLD/REPEAT never stop, stick is released. Extra DOWNs then recycle the same pointer. Next: ignore System UI flashes, do not reuse an in-use pointer id, ignore a second DOWN while that button is already held, keep the stick down across a short dead-zone blip.
- [ok] v67: buttons and move-stick work. Look stick not tried. First-screen hang + low icon is BUG-008.

### Not tried

- Look-stick confirmation.

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

## BUG-008 — Game first screen hangs; menu icon too low until minimise

**Status:** in progress
**Reported:** 2026-08-18 on Oppo A40, PadMap v67

Buttons and move-stick work. On game start the first screen hangs and the menu icon sits slightly lower than usual. Minimise + reopen puts the icon back and some games then load (some never do). After that reopen, mapped zones do nothing and games with their own controller support also get no pad.

### Cause

`showPlayCatcher()` adds a focusable `TYPE_ACCESSIBILITY_OVERLAY` and `requestFocus()`. The game never gets window focus, so splash/first activity waits. Status bar stays up; `pinIconTopCenter` then adds the status-bar inset on top of an overlay that is already laid out below the bar, so the icon sits too low. After recents, the game is focused/immersive, inset is 0, icon is correct.

The same catcher `return true`s every gamepad key, so native controller support never sees the pad. Buttons mashed on the hung splash stay in `activeHolds`; recents is treated as System UI (not a leave), so those holds are not released. After reopen, a new press is `already down` and inject is skipped — zones look dead, and `onKeyEvent` still consumes the key.

A11y already delivers pad keys/motion (v67). The catcher is not needed to play.

### Attempts

- [in progress] Do not add the focusable play catcher. Pin the icon at `y=4dp` with no extra status-bar inset, and re-pin after the game has a moment to go immersive.
- [in progress] Release playback when leaving a game and when entering one. Only filter keys in a mapped game (or config). Pass the key through if we are not actually injecting. Re-down a stale hold instead of ignoring it.
- [in progress] v67 dump: inject works after the overlay ✕. That ✕ is `closeAndDisableIcon` — it removes the config overlay and does **not** re-add the catcher, so the game gets focus and stale holds are already cleared. Game start never runs that kick (`repositionForGame` no-ops if the icon is not created yet; `serviceInfo` is only written when flags change). Next: same kick on game enter and on ✕/SAVE (`restoreGamePackage` + always assign `serviceInfo`). ✕ closes the menu and keeps the icon.

### Not tried

- Device confirmation.

---

## BUG-011 — Look-stick pan is jittery; SPD shown as px; no invert-Y

**Status:** in progress
**Reported:** 2026-08-18

Look pan stutters. Tuning SPD is labelled in pixels, which does not read as how fast the camera turns. Need a vertical invert on the stick.

### Cause

Look mode walks the pointer in `lookSpeedPx` steps and, at the zone radius, `pointerUp` + `pointerDown` at centre. That reset is a hitch every few hundred ms. Ticks are not time-based, so sidecar delay makes the swipe uneven.

### Attempts

- [in progress] Time-based pan (SPD 1–20 = turn rate, not px). Soften axis, recycle past a wider sweep with a down slightly along the look direction (not at centre). Invert-Y toggle on stick tune.

### Not tried

- Device confirmation.

---

## BUG-010 — Turbo press closes the overlay and kills all controls

**Status:** in progress
**Reported:** 2026-08-18 on PadMap v68

Set a zone to turbo, press that button: the overlay menu closes and afterwards no controls work.

### Cause

Turbo `fireTaps` every 100ms. Between taps the menu icon is touchable again, so an injected down can hit the icon (`enterConfigModeFromIcon`), which `releaseAllPlayback`s and opens CONFIG. In-flight taps then land on the full-screen overlay and can hit ✕ (`exitConfigMode`). Pointer ids also leak if tap duration ≥ interval (10 slots gone → nothing allocates).

### Attempts

- [in progress] Keep the icon not-touchable for the whole turbo/repeat job, not just each tap. Ignore icon taps while playback is busy. Drop in-flight taps if CONFIG opens or playback is released. Reuse one pointer per turbo tap instead of leaking the pool.

### Not tried

- Device confirmation.

---

## BUG-009 — Zone under the menu icon does not fire until the icon is moved

**Status:** in progress
**Reported:** 2026-08-18 on PadMap v68

If the floating menu button sits on top of a mapped zone, pressing that pad button does nothing until the icon is dragged off the zone.

### Cause

The icon is a touchable `TYPE_ACCESSIBILITY_OVERLAY`. Injected downs/moves at that coordinate hit the icon window, not the game.

### Attempts

- [in progress] While any hold/stick/tap is active, set `FLAG_NOT_TOUCHABLE` on the icon so injects pass through to the game. Restore tappable flags when playback is idle.

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
