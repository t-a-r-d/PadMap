# Mantis reliability comparison — 2026-08-20

The supplied `Mantis Gamepad Pro.apk` was inspected statically. It contains
separate activation/ADB, calibration, normalized-input, pointer, button, action-queue,
and touch-queue components. Class names alone do not prove runtime timing, but they
support a design where input state is separated from ordered touch injection.

## PadMap adoption: ordered non-blocking playback

PadMap already has one sidecar I/O writer and a single batch command for both stick
positions. Before this change, gameplay callers synchronously waited for the writer's
socket acknowledgement, potentially for three seconds. A button DOWN/UP could therefore
block the accessibility callback while the next stick frame waited behind it.

The first adoption keeps the existing binary protocol and writer. Gameplay commands are
queued through it: DOWN, UP, RELEASE, reconnect probe, and batched stick MOVE. Their
submission order is preserved, but accessibility callbacks do not wait for an ACK.
A duplicate DOWN for an already-held button is a no-op until the matching UP arrives.

## PadMap-native recovery and normalization

The sidecar now exposes a monotonically increasing failure generation. Before handling
new key or motion input, PadMap compares it with the last reconciled value. A new failure
while playback owns pointers releases the local ownership and queues one injector release,
so a future physical DOWN starts cleanly rather than inheriting stale state.

Stick axes are normalized from Android's `InputDevice.MotionRange`: centre and span become
the standard -1..1 range and the controller's reported flat range becomes a zero region.
Triggers are normalized separately to 0..1. This is PadMap code and does not use Mantis
source or assets.

## Follow-up validation

On device, use two mapped stick zones with four simultaneously held mapped buttons.
Check continuous movement/look, hold persistence, Home/menu return, and injector recovery.
Inspect DEBUG/SHARE output for sidecar errors and pointer state. Do not change tick rate or
reintroduce time-slicing unless this test shows a remaining measured queue problem.
