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
  lands before the pen tip, so proximity must close the gate. Traps: stylus hover is delivered
  to `onHoverEvent` (pointer-source), NOT `onGenericMotionEvent` — handle both; tap-like host
  gestures must re-check the gate at finger-**up**; and tap-*actions* must commit after a
  `PEN_ACTIVE_TAIL_MS` **escrow** (drop if the gate closes meanwhile) — a palm micro-tap can
  complete ~190 ms before the pen enters hover range (measured NA5C), invisible to any proximity
  signal at up-time. Contact size is no substitute: EPD touch panels may report zero
  size/touchMajor with palms classified as plain finger (NA5C does).
- **Onyx proximity is off by default.** With the raw pipeline open, BOOX delivers NO pen-approach
  signal at all — no hover MotionEvents, no `onPenActive` — until
  `TouchHelper.setPostInputEvent(true)` (bytecode-verified master switch, default off; set in
  `openRawDrawing`). With it on, subscribe `TouchHelper.register(...)` to the SDK event bus for
  `PenActiveEvent`/`PenDeactivateEvent` (greenrobot; enter/leave EMR range, 100 ms-timeout exit) —
  these feed `markPenInRange`/`markPenOutOfRange` on the shared gate. Events arrive on the raw
  input thread; the gate fields are volatile.
- **Ratta bake-handoff ordering (fourth overlay law, found here — not in the reference).** The
  deferred bake must issue `clearAll` **between** the node record and the `invalidate`
  (`RattaPaperView.redrawCommitted`): the daemon pairs a clear with the next app frame it sees, and
  a clear issued after the invalidate can pair with a stale in-flight frame recorded *before* the
  bake — the overlay drop then reconciles against stroke-less pixels and the just-written ink
  visibly vanishes until a later repaint damages the region. The reference never hit this because
  its hosts present no frames mid-writing; g-paper hosts may render at input rate (the demo's raw
  counter does), so the clear ladder also arms after **every** bake handoff — post-bake, every
  possible frame contains the strokes, making retry pairs harmless when the handoff landed and a
  ≤450 ms self-heal when it didn't.
- **Selection/lasso is shared-base machinery** (`CanvasPaperView`): device engines add only trail
  chrome + EPD drag handling, driving the base's protected seams from their pipelines. Trails are
  engine chrome, never model data. The stylus-only contract has ONE exception: while a selection is
  active, a single finger drags it / a finger tap dismisses it (palm-gated: `isPenActive` refusal,
  mid-drag pen cancel, multi-touch kill, `PEN_ACTIVE_TAIL_MS` escrowed dismissal) — hosts with touch
  listeners on the paper view must yield finger events while a selection is active. Host content
  joins drags via the optional `ContentRenderer` pair (exclusion-aware `draw` + `drawObject`);
  implement both or neither.
- **Onyx: frames presented during a live raw contact are withheld from the panel**, and a pen-up
  `invalidate()` of identical content is damage-free — the panel never repaints. Any overlay-chrome
  change made at pen-down (e.g. tap-away selection dismissal) needs an explicit
  `handwritingRepaint` at contact end **plus a ~250 ms retry** (the immediate one races the SDK's
  end-of-contact processing and can be eaten). Related: the lasso trail render must arm at the
  first *move* sample, never at pen-down — a tap that arms the overlay freezes its own dismissal
  frame and its wipe loses that race.
- **Ratta: never issue an armed gesture-trace clear at ACTION_DOWN of an inking contact** — the
  daemon pairs the clearAll with a frame presented a beat *into* the new contact and eats its first
  ink (lasso-trail starts visibly wiped on the Nomad; the Manta's faster daemon hides it; latent in
  the reference, which flushed at down in all modes). Flush from the **hover stream** instead (once
  per ladder arming, `flushArmedOverlayClearOnApproach`) — law 3's pre-contact channel; the
  down-time flush is kept only for erase contacts, whose overlay ink is unwanted anyway.
- The Ratta engine is selected only when Supernote hardware **and** the firmware ink binder are
  both present (`isRattaDevice() && SupernoteInk.isAvailable()`); absent either, selection falls
  through (that probe fall-through is engine *selection*, not a runtime fallback). With the binder
  absent, `RattaPaperView` itself degrades to generic-style behavior (`rendersLiveStrokes = !firmware`).
- Engine selection: explicit registration via `GPaper` (no ServiceLoader, no reflection). Engine
  choice happens once at creation, logged at `Log.i`; **never add a silent runtime fallback** —
  post-construction engine failures must be loud.
- Hosts own all data; ids are the join key. `clear()` fires no erase callbacks; page turns are
  `clearForContentSwap()` + `loadStrokes()`; `onPenLifted` is a save trigger only.

## Toolchain (mirrors Notesprout)

- Gradle 8.14 (wrapper), AGP 8.11.1, Kotlin 2.2.20
- JDK 17 Temurin, pinned via `org.gradle.java.home` in `gradle.properties`
- compileSdk 35, minSdk 29, targetSdk 35 (demo)
- Jetifier + the insecure BOOX maven repo (`http://repo.boox.com/...`) are enabled since Phase 3
  for `gpaper-onyx` (onyxsdk-device 1.3.3, onyxsdk-pen 1.5.4, hiddenapibypass 4.3 — all
  `implementation`-scope). **Consumers that skip gpaper-onyx need neither**; consumers using it
  add both to their own build. The Onyx AAR manifests carry an application label — apps need
  `tools:replace="android:label"`. The demo ships arm64-v8a only + `libc++_shared.so` pickFirsts.
- **`gpaper-ratta` adds zero dependencies** — it drives the Supernote firmware's ink daemon
  directly over Binder (raw `Parcel` transactions, reflection on `ServiceManager`/the `eink`
  service). Consumers need no extra repo, no jetifier, nothing.

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
- BOOX devices spam logcat (`test_keymap` etc.) hard enough to wrap the buffer in seconds — debug
  with `adb logcat -G 16M` plus a **streaming** filtered capture (`logcat -s TAG`), never `-d` after
  the fact. Also seen on NA5C: `install -r` + immediate `am start` can race package finalization,
  leaving the package installed but **disabled** (`enabled=3`, "Activity class does not exist") —
  heal with `pm enable <pkg>`.
- BOOX has a real status bar overlaying the window top (Supernote has none) — host layouts must
  apply system-bar insets; the demo pads its root via `setOnApplyWindowInsetsListener`.

## Working style

- Deliberately slow and careful; frugal with background agents/tokens — work mostly inline.
- Each phase: plan → build → test → commit & push when green. Update `PLAN.md` statuses the moment
  they change; fold new durable knowledge into this file at phase close-out.
