# PadMap — Project Brief for Claude Code

## Project Overview

Native Android gamepad mapper app. Maps physical gamepad inputs (buttons, sticks, triggers) to on-screen touch targets for games that don't support controllers. Uses AccessibilityService for input dispatch and overlay rendering.

- Platform: Android only (Kotlin)
- Package: TBD (check app/build.gradle)
- APK output: `/mnt/chromeos/MyFiles/Downloads/appProjects/PadMap/`
- APK name: `PadMap-debug-v<N>.apk`
- Notes file: `PadMap-debug-v<N>-notes.txt`

## Platform & Tech

- Native Android / Kotlin
- AccessibilityService — input interception and touch dispatch
- Overlay (TYPE_APPLICATION_OVERLAY) — on-screen mapping UI drawn over other apps
- Input events: `onGenericMotionEvent` (axes), `onKeyEvent` (buttons)
- Axis constants: AXIS_X/Y (left stick), AXIS_Z/RZ (right stick), AXIS_HAT_X/Y (D-pad), AXIS_LTRIGGER/RTRIGGER
- No unnecessary third-party packages

---

## Pre-Build Check-In — MANDATORY

Before bumping the version code and running any APK build, cc must ask Damien:

> "Is there anything else you'd like to add before the APK is built?"

**CC must never build the APK without Damien's explicit permission.**

Rules:
- After asking, wait for Damien's response.
- If Damien adds more changes, implement them, then ask the check-in question again.
- Keep asking after each round of changes until Damien explicitly says to build ("build it", "go ahead", "yes", "nothing else").
- Do not interpret silence, "continue", or any ambiguous reply as permission to build.
- Only start the build — bump version code, run Gradle, copy APK — after Damien has clearly confirmed.

---

## APK Versioning — Never Overwrite a Released Build

Once an APK has been copied to the downloads folder, that version number is closed.

Rules:
- All instructions and changes Damien gives after a build is complete are for the **next** version.
- Before every build, check the downloads folder for the highest existing APK number and increment by 1.
- Never copy a new APK over an existing file of the same version number.
- If a bug is found and fixed after a build, the fix ships in the next version.

---

## No-Assumption Development Rules

- **Read every related file before writing any code that touches shared infrastructure.** Services, managers, and overlay components are interconnected. Before modifying any one of them, read all files that interact with it.

- **Before writing any input routing or event-handling logic: enumerate all event paths first.** `onGenericMotionEvent` and `onKeyEvent` have separate processing paths. A filter that works for button events may silently break axis handling.

- **Never assume a fix works — state what was wrong and why the fix resolves it.** Say what the broken line was and exactly why the replacement resolves the mismatch.

- **Before writing any conditional check, enumerate all cases it will match.** For every `if` or `when`, confirm none of the inputs it matches are unintended.

- **Before modifying any Service or Activity, read its full lifecycle context.** AccessibilityService lifecycle is distinct from Activity lifecycle — understand when `onServiceConnected`, `onInterrupt`, and `onAccessibilityEvent` fire before modifying anything.

- **When a bug is reported, read the log completely before forming a hypothesis.** Do not anchor on the first matching symptom.

- **Do not batch multiple fixes into one build unless they are independently verified to be correct.** Prefer one targeted fix per build.

- **CC cannot run the app and cannot verify fixes at runtime.** Every "fix" is a code-level correction only. The only verification is the user testing the APK.

- **Before calling any shared function, read its full implementation.** Never assume a function does only what its name suggests.

- **Use the narrowest possible action — never a broad reset when a targeted one will do.**

- **Every fix must be traced through all callers and all side effects before shipping.**

- **After writing any fix, re-read the changed code top to bottom and ask: what else does this affect?**

---

## CRITICAL — Code Change Rules

- **`@~` markers in debug notes are mandatory instructions.** Every single `@~` item must be acted upon — none may be omitted, deferred, or ignored.

- **Read the entire debug notes document before writing any code.**

- **Generate an interpretation document before coding (when requested).** Save as `PadMap-debug-v<N>-cc_interpretation_nts1.txt` in the same directory as the debug notes. The document is iterative: `nts1`, `nts2`, etc.

- **Interpretation notes are optional — follow Damien's instructions.** If Damien does NOT request interpretation notes: code the changes first, then ask the mandatory pre-build check-in question before building.

- **`[?]` in a returned test checklist means the item could not be tested.** Carry the tester's comment into the Q&A section of the interpretation notes.

- **Questions for Damien must be flagged in the VSCode output.** Explicitly state: "There are questions for you in the interpretation notes."

- **Pre-fill answer slots in the interpretation document Q&A.** Format each as `A<N>:` immediately below the question.

- **The PENDING / DEFERRED section belongs to Damien — CC never adds or removes items.**

- **NEVER change code that was not explicitly requested.**

- **Preserve all previously built features.** Before editing any file, read it fully and confirm every existing feature will still be present.

- **Do not add improvements, refactors, or "while we're here" changes.**

- **ADDITIONAL TASKS in test notes are mandatory.** Never silently skip one.

- **Reuse existing code before writing from scratch.**

- **When asked to make something "the same as X", read X first.**

- **Work precisely to the tester's instructions without deviation.**

---

## General Preferences

- Complete, ready-to-build project deliverables — not partial code snippets
- Production-ready and well-structured output
- As lightweight as possible — no unnecessary dependencies
- Build custom tools ourselves; only reach for a package when the task is genuinely beyond what we can write

---

## Android Build

### Version Number
Before every APK build, check the downloads folder for the highest existing APK and increment by 1. Never overwrite an existing APK.

### Build Command
```bash
cd /home/slickstax841/appProjects/PadMap && ./gradlew assembleDebug
# Copy to: /mnt/chromeos/MyFiles/Downloads/appProjects/PadMap/PadMap-debug-v<N>.apk
```

### Build Notes Format
```
PadMap — Debug Build v<N>
================================
Built: YYYY-MM-DD


WHAT'S NEW IN v<N>
-----------------
<numbered list>


Q&A RESPONSES
-------------
<Only when tester filled in Q&A previously.>


TEST CHECKLIST — v<N>
---------------------
[ ] <action> → <expected result>


ADDITIONAL TASKS
----------------
1)
2)
3)
4)
5)


Q&A
---
1)
2)
3)
4)
5)


PENDING / DEFERRED
------------------
- <item> (<reason>)
```

Rules:
- Group checklist items by feature — one heading per group
- Checklist titles must be specific: "LEFT STICK — Map to screen left zone → drag follows stick X/Y" not just "STICK"
- Every changed feature must have at least one checklist item
- Carry PENDING / DEFERRED forward to every build — never drop an item unless told to

---

## Documentation-First Development Rule

Before writing any code for a native Android feature, CC must:
1. Read the official Android documentation for that feature.
2. Search for real-world implementation examples.
3. Save a reference to `~/appProjects/PadMap/dev-refs/<topic>.md`.
4. Only then write code to what the documentation specifies — never guessing at API behaviour.
