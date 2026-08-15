# CLAUDE.md — g-paper project intelligence

g-paper is an Android library that embeds a writable/drawable "paper" surface in any app.
`PLAN.md` is the cross-session memory: phase statuses, locked decisions, and the working protocol
(phase rituals, context resets) live there. This file holds standing rules and build facts that
apply regardless of phase.

## Layout

Gradle multi-module project at repo root:

- `gpaper-core` — public API + generic Canvas engine (`com.symmetricalpalmtree.gpaper.core`)
- `gpaper-onyx` — BOOX adapter (`…gpaper.onyx`), depends on core
- `gpaper-ratta` — Supernote adapter (`…gpaper.ratta`), depends on core
- `demo` — demo app (`com.symmetricalpalmtree.gpaper.demo`)

Reference source: `~/git/Notesprout` (see its `docs/drawing-engine.md`) — the engines being
extracted/redesigned. Read it for device knowledge; never copy its sibling-view duplication.
Pen-type/native-style knowledge: Notesprout `docs/onyx-pen-tools.md` (Onyx five-device survey) and
the Ratta 0…31 pen-code sweep recorded in Notesprout's `app/src/debug/AndroidManifest.xml` comment.

## Public API (Phase 1 — standing rules)

- The whole host-facing surface is `gpaper-core`'s `com.symmetricalpalmtree.gpaper.core` package;
  `docs/api.md` is the guided tour and must be kept in step with the code.
- **`model/` and `geometry/` stay pure Kotlin — no Android imports.** That is what lets the unit
  tests run on the JVM without Robolectric. Android bridging goes in `model/AndroidInterop.kt`.
- Native device style/pen codes never surface in the public API. `StrokeStyle` is abstract; the
  committed (baked) appearance is core-rendered and portable across engines, live ink is a
  best-effort per-engine mapping (tables in `StrokeStyle` KDoc + `docs/api.md`).
- `core/canvas/` is the generic engine + shared canvas base (`CanvasPaperView`, `StrokeRenderer`).
  Public **only** so device modules can subclass — never present it as host API; hosts go through
  `GPaper.create`. `StrokeRenderer` is the single source of truth for committed stroke appearance.
- **Pen-activity gate includes hover.** `isPenActive` = writing ∨ hovering + 350 ms tail — the palm
  lands before the pen tip, so proximity must close the gate. Two traps: stylus hover is delivered
  to `onHoverEvent` (pointer-source), NOT `onGenericMotionEvent` — handle both; and tap-like host
  gestures must re-check the gate at finger-**up** (palm can land before the pen enters hover range).
- Engine selection: explicit registration via `GPaper` (no ServiceLoader, no reflection). Engine
  choice happens once at creation, logged at `Log.i`; **never add a silent runtime fallback** —
  post-construction engine failures must be loud.
- Hosts own all data; ids are the join key. `clear()` fires no erase callbacks; page turns are
  `clearForContentSwap()` + `loadStrokes()`; `onPenLifted` is a save trigger only.

## Toolchain (mirrors Notesprout)

- Gradle 8.14 (wrapper), AGP 8.11.1, Kotlin 2.2.20
- JDK 17 Temurin, pinned via `org.gradle.java.home` in `gradle.properties`
- compileSdk 35, minSdk 29, targetSdk 35 (demo)
- Jetifier and the insecure BOOX maven repo (`http://repo.boox.com/...`) are **deliberately absent**
  until Phase 3 (`gpaper-onyx` SDK work); generic-only consumers must never need them.

## Build & device testing

- `./gradlew build` — full build. `./gradlew :demo:assembleDebug` → `demo/build/outputs/apk/debug/demo-debug.apk`.
- Install to devices with the `device-build-install` skill (`.claude/skills/device-build-install/`),
  which holds the ADB serial + tier table. Users refer to devices by nickname (G10, MAX, SNN…).
- EPD pen overlays are **invisible to screencap** — ink behavior is verified by the user's eyes on
  real BOOX/Supernote hardware. App UI (non-ink) does show up in screencap. The **generic engine's
  ink is ordinary View rendering and IS visible to screencap** (useful even on EPD devices while no
  device adapter is registered).
- adb `input` injection cannot exercise stylus paths: injected events carry toolType UNKNOWN on
  Supernote (both `input tap` and `input stylus swipe`), so the engine ignores them. Injected taps
  do drive click listeners — fine for toolbar/UI checks; pen behavior needs real hands.
- The Supernote Manta reports itself as a Nomad in every `ro.product.*` property; the ADB serial is
  the only reliable way to tell them apart. `Build.MANUFACTURER` is `"Supernote"`, not `"ratta"`.

## Working style

- Deliberately slow and careful; frugal with background agents/tokens — work mostly inline.
- Each phase: plan → build → test → commit & push when green. Update `PLAN.md` statuses the moment
  they change; fold new durable knowledge into this file at phase close-out.
