# PadMap — Bug Tracker

Each entry covers one bug: what it is, every fix attempt and its outcome, and what hasn't been tried yet.
CC reads this before investigating any bug.

---

## BUG-001 — Wireless debugging “connected” toast loops (Oppo A40)

**Status:** fix written, not on device yet (v54 is still the shipped APK)
**Reported:** 2026-08-18 on Oppo A40 / ColorOS, PadMap v54

After pairing, PadMap reaches wireless ADB, then Android’s “Wireless debugging connected” toast keeps sliding down. Looks like connect → drop → connect.

### Cause

Home `ON_RESUME` calls `SidecarHost.ensureRunning`. `connect()` shows ColorOS’s toast, which pauses/resumes PadMap. v54 has no in-progress lock, so the next resume starts another `start()` → `connectAny()` `disconnect()`s the first session and `connect()`s again. Sidecar never stays up, ping keeps failing, toast loop.

### Attempts

- [in progress] One auto-connect per process from Home resume. Overlapping `ensureRunning` returns immediately. Drop ADB after the sidecar pings (sidecar is localhost; holding wireless ADB retriggers ColorOS). Settings **START** still force-retries. Not on an APK until Damien says to build.

### Not tried

- Device confirmation on A40 after the next APK.

---

## How to use this file

When a bug is reported:
1. CC reads this file first — never attempts a fix that's already been tried
2. CC adds an entry before writing any code
3. After each attempt (pass or fail), CC updates the entry and pushes to GitHub
4. Root cause theories are listed as checkboxes and worked through in order
