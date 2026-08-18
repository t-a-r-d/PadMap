# PadMap — project-specific

**SHELVED.** Do not read this file unless Damien opened `~/appProjects/PadMap/` this session.
Global rules: `~/PROJECT_RULES.md`.

## Overview

Native Android gamepad mapper. Maps physical gamepad inputs to on-screen touches via
AccessibilityService and `TYPE_APPLICATION_OVERLAY`.

- Source: `~/appProjects/PadMap/`
- APK: `/mnt/chromeos/MyFiles/Downloads/appProjects/PadMap/PadMap-debug-v<N>.apk`
- Build: `cd ~/appProjects/PadMap && ./gradlew assembleDebug`

## Input

- `onGenericMotionEvent` — axes
- `onKeyEvent` — buttons
- AXIS_X/Y left stick, AXIS_Z/RZ right stick, AXIS_HAT_X/Y D-pad, AXIS_LTRIGGER/RTRIGGER

A filter that works for buttons can silently break axes. Enumerate both paths before changing routing.

Playback is a shell sidecar (`sidecar/SidecarMain.java`). Magisk `su` copies and
starts it as uid 2000 (shell) when `su` exists. Wireless ADB pairing is only used
when there is no `su` binary. The app never calls `injectInputEvent`. Overlay is
placement only.
Move stick is absolute (`centre + axis * radius`). Look stick is relative
(`+= axis * speed`, reset at edge). Buttons are pointer down/up on that sidecar.

## Extra pre-build ask

Before a version bump, also ask:
> "Is there anything else you'd like to add before the APK is built?"

Keep asking after each extra round until Damien confirms. Notes only when he asks.
Documentation-first refs: `dev-refs/<topic>.md`.
