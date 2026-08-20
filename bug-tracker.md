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
- [in progress] Requesting `motionEventSources` JOYSTICK|GAMEPAD always meant the game never saw analog (built-in R-stick look died with no look zone). Only request stick motion when a stick zone has override. Assigned buttons still consume that key so in-game A cannot fire.

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

## BUG-012 — Stick zone options only open from the inner circle

**Status:** in progress
**Reported:** 2026-08-18

Tapping a stick zone should open its options from the centre and from the ring between the blue dead-zone and the orange outer limit. Only the small inner disc did.

### Cause

`ZoneCircleView` treats MOVE (which opens the menu on up) only when `dist < innerRadius + 20`. The rest of the disc out to `outerRadius` returns false, so the tap never opens options.

### Attempts

- [in progress] Assigned sticks: tap anywhere inside the orange ring (except the resize/dead-zone dots) is MOVE and opens options. Drag still moves the zone.

### Not tried

- Device confirmation.

---

## BUG-013 — Overlay home SAVE clipped on the panel edge

**Status:** in progress
**Reported:** 2026-08-19 on PadMap v71

The overlay home card clips SAVE on the right edge. ADJUST, DEBUG, CLEAR, SAVE do not have equal gaps.

### Cause

`buildTopPanel` sets `minimumWidth = 200dp` and the button row is `MATCH_PARENT` with a weight-1 spacer after DEBUG. The card stays ~200dp plus padding; leftover width after ADJUST + DEBUG is too small, so SAVE is clipped.

### Attempts

- [in progress] Drop the 200dp floor. Button row `WRAP_CONTENT` with equal 8dp gaps and no weight spacer so the card grows to ADJUST, DEBUG, CLEAR, SAVE.

### Not tried

- Device confirmation.

---

## BUG-017 — Button size +/-; LT/RT repeat-tap on hold

**Status:** in progress
**Reported:** 2026-08-19

Button options should size the zone with a scrubber on an arc from 8 o’clock (smallest) over the top to 2 o’clock (largest), not −/+. Holding LT/RT repeat-taps; they must hold. Repeat only via turbo.

### Cause

`showContextMenu` uses −/+. Analog triggers keep sending extra `ACTION_DOWN` (repeatCount 0). `onButtonDown` releases and restarts the hold on re-down, which looks like turbo. Analog LT/RT axes are not turned into a single press/release.

### Attempts

- [in progress] Arc scrubber on the options disc (8→2 over the top). Trigger axes with hysteresis; ignore LT/RT re-down while already holding; key-up ignored while the analog trigger is still down.

### Not tried

- Device confirmation.

---

## BUG-025 — 8ms look/move mux failed badly

**Status:** in progress
**Reported:** 2026-08-19

Time-slicing move and look made control worse. Remove it.

### Cause

Mux lifted one stick every 8ms so the game saw a tapping finger.

### Attempts

- [in progress] Drop mux. Both sticks stay down; look wrap is the old UP/DOWN again.

### Not tried

- Device confirmation.

---

## BUG-024 — Overlay mode and layer binds live in the app Home, not the overlay

**Status:** in progress
**Reported:** 2026-08-19

AUTO/LAND/PORT belong in the overlay editor. Layer activate/deactivate belong top-left of the overlay home, not inside the home card, with a third RETURN layer (default previous).

### Cause

HomeScreen owned OverlayFitBlock and LayersBlock. Overlay home card had only the 1–6 edit row. Deactivate always switched to layer 1.

### Attempts

- [in progress] Mode chips on the overlay home card. Layers dock top-left: 1–6, ACTIVATE, DEACTIVATE, RETURN. Deactivate uses returnLayer or the layer in use before activate.

### Not tried

- Device confirmation.

---

## BUG-023 — Playback debug too thin to diagnose crashes

**Status:** in progress
**Reported:** 2026-08-19

Need a fuller event sequence (buttons, sticks, mux, wrap, crash) that survives process death, plus SHARE to paste here.

### Cause

`PlaybackDebug` kept 50 in-memory lines, throttled motion hard, and died with the process. Overlay SHARE existed; Settings only shared the injector dump.

### Attempts

- [in progress] 400-line ring written to file, crash hook, richer snapshot, SHARE PLAYBACK in Settings.

### Not tried

- Device confirmation.

---

## BUG-022 — Zone settings lost; look jumps at the edge

**Status:** in progress
**Reported:** 2026-08-19

Tune/size/turbo/look mode must stick until the user changes them. Look sometimes leaps back from the far edge in another direction, even with move unused. If dual-stick is still hard, alternate them fast enough that it does not show.

### Cause

`ButtonTuningStore` is memory-only. Layout mappings only write on SAVE. Look recycle used MOVE-warp whenever `turboDown` or another pointer was set, so the finger teleported toward centre. Dual-stick UP/DOWN hitch remains if both pointers stay down.

### Attempts

- [in progress] Persist tuning + auto-write mappings on each zone change. Solo look uses the old UP/DOWN wrap. Both sticks: 8ms single-pointer mux.

### Not tried

- Device confirmation.

---

## BUG-021 — Options disc small; zones hard to drag; sticks judder / app quits

**Status:** in progress
**Reported:** 2026-08-19

Options ring is too small. Zones should drag to a new place. Move + look together judder; many buttons or both sticks can quit the app.

### Cause

Disc is 100dp; zone drag waits 20dp so a pull often opens the menu instead. Look recycle does sync `pointerUp`/`pointerDown` on the tick thread while move is still down — that hitchs the other finger and can block main long enough for the service to be killed. Ten live pointers plus look recycle can also blow the inject stream.

### Attempts

- [in progress] Larger disc. Drag slop 8dp (none while selected). Look wraps with MOVE when another pointer is down. Cap live pointers so inject does not overflow.

### Not tried

- Device confirmation.

---

## BUG-020 — Turbo belongs on the options ring; home overlaps the cluster

**Status:** in progress
**Reported:** 2026-08-19

Turbo on/off is in the tune box. It should be a ring button with the other zone options. While the ring is open the home card overlaps it; home should slide aside and return when the ring closes.

### Cause

`buildTuningContent` owns the TURBO row. `showContextMenu` does not move `configPanel`.

### Attempts

- [in progress] T on the ring toggles turbo (orange when on). Tune keeps duration/interval only. Park home off the cluster without writing `panelX`/`panelY`; restore on dismiss.

### Not tried

- Device confirmation.

---

## BUG-019 — Options cluster too big; size scrubber not on the top arc

**Status:** in progress
**Reported:** 2026-08-19

The options disc is large and the actions read as a vertical set. Size track should sit further out around the top (8→2 over the top, not under the disc). The knob must follow a finger along the arc and jump to a tap on the track.

### Cause

`discSize` is driven by `orbit = btnSize + gap` (~172dp). Few actions start at 12 o’clock so two buttons land at 12 and 6. Track gap is 6dp. Scrubber maps raw `atan2` with `coerceIn(150,330)`, so a finger off that range jumps and feels like an up/down slider.

### Attempts

- [in progress] Smaller disc, buttons on an inner ring starting at 8 o’clock. Track farther out on the top 8→2 arc. Nearest-point-on-arc for tap and drag.

### Not tried

- Device confirmation.

---

## BUG-018 — Other buttons dead while a turbo button is held

**Status:** in progress
**Reported:** 2026-08-19

While a turbo zone is repeat-tapping, other mapped buttons do nothing. They must still fire; interrupting the current turbo tap is fine.

### Cause

Each turbo tap allocates a pool pointer and may stay down across the next interval. The pool empties (`no pid`) so `startHold` never injects. A turbo finger still down also blocks single-touch games from seeing a second button.

### Attempts

- [in progress] One reserved pointer per turbo zone. Another button first lifts in-flight turbo taps, then holds. Turbo ticks skip while any hold is down and resume when it lifts.

### Not tried

- Device confirmation.

---

## BUG-016 — Stick needs a toggleable walk-tap zone; drop COPY

**Status:** in progress
**Reported:** 2026-08-19

Stick options should add/remove a button-style zone that repeat-taps while that stick is moved. COPY on button options is unused — make a new zone the usual way.

### Cause

No parent-linked tap mapping. `showContextMenu` still has COPY. Playback only tap-repeats from button turbo jobs.

### Attempts

- [in progress] Stick option ↻ creates a `parentZoneId` tap zone (move/size/tune like a button). Stick deflection starts the same repeat-tap path; centre/release stops it. COPY removed.

### Not tried

- Device confirmation.

---

## BUG-015 — Overlay adjust edges move the whole window; centre is orange

**Status:** in progress
**Reported:** 2026-08-19

Dragging the highlighted adjust border moves the entire overlay instead of resizing. Edge drag feels like it needs a press-and-hold. The centre wash is orange; Damien wants a more transparent blue centre, orange only on the borders.

### Cause

`showAdjustLayer` uses leftover `onTouchEvent` on the full layer for window-move. Edge strips are not clickable and lose the gesture, so the parent treats the drag as a move. `enterAdjustMode` paints the whole root orange (`argb(70, 255, 140, 0)`).

### Attempts

- [in progress] Layer intercepts non-Save touches immediately (no slop). Hit-test the 52dp bands for resize vs centre for move. Root tint back to transparent blue; orange only on the edge strips and glow.

### Not tried

- Device confirmation.

---

## BUG-014 — Zone options row is a vertical strip; active zone not highlighted

**Status:** in progress
**Reported:** 2026-08-19 on PadMap v71

With a zone open, the options sit in a vertical row beside the home card (or around the zone). Damien wants a round cluster in the screen centre, a move handle that repositions the cluster and keeps that place, and the active zone highlighted with the orange glow plus a clearer colour.

### Cause

`showContextMenu` docks DELETE / COPY / size / TUNE / MOVE as a vertical list next to `configPanel`. MOVE drags the zone, not the menu. `ZoneCircleView` always paints cyan; glow exists only on the overlay resize rim.

### Attempts

- [in progress] Round cluster on `configRoot`: options on a ring, MOVE in the centre drags the cluster, `optionsX`/`optionsY` persist. Active zone uses orange fill/stroke and the same sweep glow on its border.

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

---

## BUG-026 — Busy game input stalls, releases, or loses a hold

**Status:** source attempt complete; awaiting compile and device confirmation
**Reported:** 2026-08-20 on Oppo A40 / PadMap v76

When both sticks and several mapped buttons are used, gameplay judders and controls
can stop/start. A held button can be lost; some games leave PadMap unable to resume
after returning from its Home/menu route. One-stick games are materially more stable.

### Confirmed cause

`PadMapAccessibilityService` calls synchronous sidecar `pointerDown` / `pointerUp`
from accessibility callbacks and the stick tick. Each waits for a socket acknowledgement
(up to three seconds) on the one sidecar executor. Under a combined stick/button load,
those waits block the input callback path while batched stick frames queue behind them.
Non-trigger duplicate DOWN also releases and recreates its hold instead of being idempotent.

### Attempts

- [in progress] Keep the existing one-writer sidecar executor, but queue every gameplay
  DOWN / UP / RELEASE and stick batch without waiting on the accessibility thread. Preserve
  command order, and treat a repeated DOWN for every already-held button as a no-op.
  Source written 2026-08-20; `git diff --check` passes. No compilation or device test yet.

### Not tried

- Device confirmation with two sticks plus four simultaneous holds, then game-menu / Home
  transition and return.

## How to use this file

When a bug is reported:
1. CC reads this file first — never attempts a fix that's already been tried
2. CC adds an entry before writing any code
3. After each attempt (pass or fail), CC updates the entry and pushes to GitHub
4. Root cause theories are listed as checkboxes and worked through in order
