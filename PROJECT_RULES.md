# PadMap — Project Rules

Applies to every AI working on this repo (gpt, grok, or any later agent).

Universal hard gates live in `~/PROJECTS_CONTEXT.md`. Do not duplicate them here.

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

## Extra pre-build ask

Before a version bump, also ask:
> "Is there anything else you'd like to add before the APK is built?"

Keep asking after each extra round until Damien confirms. Notes only when he asks.
Documentation-first refs: `dev-refs/<topic>.md`.
