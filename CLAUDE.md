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
  real BOOX/Supernote hardware. App UI (non-ink) does show up in screencap.
- The Supernote Manta reports itself as a Nomad in every `ro.product.*` property; the ADB serial is
  the only reliable way to tell them apart. `Build.MANUFACTURER` is `"Supernote"`, not `"ratta"`.

## Working style

- Deliberately slow and careful; frugal with background agents/tokens — work mostly inline.
- Each phase: plan → build → test → commit & push when green. Update `PLAN.md` statuses the moment
  they change; fold new durable knowledge into this file at phase close-out.
