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

Docs (Phase 8, all under `docs/` + the root `README.md`): `api.md` (host-facing surface, guided
tour), `integration-guide.md` (per-device build setup), `architecture.md` (internals),
`host-responsibilities.md` (persistence/pages/undo/gesture patterns). **Any public-surface or
build-requirement change must update the matching doc in the same commit.**

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
- **A textured style's texture must never reshuffle** (Phase 10, `PENCIL`). Two failures, not one:
  a grain re-rolled on **reload** is a drawing that changes behind the artist's back, and a grain
  re-rolled at **pen-up** makes every mark end in a flinch, because the live preview and the bake
  run through the same renderer. So texture comes from a stateless integer hash seeded by
  `StrokeRenderer.draw`'s `seed` — never a running RNG — and callers holding a `Stroke` pass
  `id.hashCode()`. `CanvasPaperView` therefore mints the stroke id when the **contact starts**
  (`pendingStrokeId`), not at commit, so the preview seeds off the id the stroke is about to get.
  Anything that walks the path must index by **arc length from the first point**, never by input-
  point index: a stroke still being drawn must agree with the same stroke committed, and pen
  samples arrive at whatever rate the hand and the digitizer agree on.
- **The pure half of a texture belongs in `geometry/`.** `GraphiteGrain` decides *where the
  graphite lands*; `StrokeRenderer` only puts ink there. That split is what lets determinism be
  proved by a JVM test instead of asserted, and it is the pattern any later textured style follows.
- **Draw a texture as one call per darkness, never per speck.** `PENCIL` issues
  `GraphiteGrain.LEVELS` (3) `drawPoints` calls however many thousand flecks a stroke holds —
  a page of pencil re-records in a frame because of this. Few darknesses is also the *right*
  answer on EPD: tone is meant to come from how many flecks land, and more levels quietly turns a
  spatial texture back into a tonal one and hands the panel greys to dither. Paintsprout's Wacom
  app reached the same rule from the other side (`BlurMaskFilter` on a software canvas measured
  twice its single largest per-frame cost; its grain is meshes with per-vertex colour).
- **BOOX's per-pen-kind width multipliers are not cosmetic, and a live style must be chosen for
  width before texture.** The firmware's texture pens scale the width they are given before rendering
  — `CHARCOAL_STROKE_WIDTH_EXTRA_SCALE = 5.0`, `BRUSH_STROKE_WIDTH_EXTRA_SCALE = 2.0` — because their
  grain bitmaps are scaled to the stroke and below roughly 20 px no texture exists at all. `PENCIL`
  armed against `STROKE_STYLE_CHARCOAL` therefore previewed a 6 px lead at about 30 px and committed
  6, and the mark collapsed to a fifth of itself at pen-up (measured NA5C, fixed in 0.1.8 by arming
  the plain even line, style 0). **A preview that lies about width is far worse than one that lies
  about texture: width is what the hand aims with, and the artist reads the collapse as the *bake*
  being broken.** Any style armed against a texture pen must account for its scale factor or accept
  the same collapse — `CROSS` still arms charcoal on purpose, because there it is approximating a
  texture rather than a width.
- **Tilt is supplied per measured model and zero everywhere else (Phase 11).** There is no
  `getMaxTilt()` in the SDK and the fleet reports the raw numbers on incompatible scales (one model
  in the thousands), so `gpaper-onyx` carries an allowlist of models whose tilt has actually been
  *measured* and reports `0` for the rest. **Measured on the NoteAir5C: `hypot(tiltX, tiltY)` is
  degrees from vertical, directly** — a hand at a deliberate 45° read 44.3 over 1400 samples.
  **Adding a model to that list is a measurement, never an inference from a similar-looking one.**
  `tilt = 0` is not a degraded mode — it means a pencil held upright — so a renderer must still look
  right there, and an unmeasured device gets a fixed-width pencil rather than a broken one.
- **A live preview that lies about WIDTH is far worse than one that lies about texture**, because
  width is what the hand aims with — and the artist reads the collapse at pen-up as the *bake* being
  broken, which sends the search to the wrong half of the system. When a firmware style and the bake
  disagree on size, **match the firmware** rather than flattening the style: `TouchHelper` exposes
  only style/colour/width (verified by `javap`), so a textured live style cannot be had without
  whatever tilt response it comes with.
- **A cross-section must be laid across the SMOOTHED direction of travel.** Taking the pen's
  direction from one adjacent pair of raw samples measures jitter, not travel: at 2 px spacing,
  0.35 px of digitizer noise swings it ~14° sd, past ±35°. Every cross-section of grain is rotated by
  that much and **the error is multiplied by the half-width of the mark** — on a wide stroke it throws
  grain tens of pixels out of line and the mark grows bristles ("pipe cleaner", 0.1.16). Smoothed over
  ~10 px of arc, causally. Paintsprout's Wacom app builds its mesh normals the same way and has the
  same fault.
- **A stroke's cap is the lead's CONTACT PATCH, and a tilted lead's patch is an ellipse.** It smears
  many times sideways but still leaves the paper over the width of the lead, so a cap reaches the
  lead's radius along the stroke and the mark's half-width across it (0.1.19). Capping with a
  half-disc of the mark's own half-width puts a blob on the end of a broad stroke.
- **A filter added to remove noise brings a transient of its own, and a stroke's start puts it on
  display.** The tangent smoother (0.1.16) was seeded from the first pair of samples and began every
  broad mark with a hook — the touch-down cap thrown along a wrong heading, plus the filter swinging
  as it converged, both scaled by the half-width. Seed from a **chord across the whole smoothing
  window** instead: no transient, because the seed is already where the filter would settle. Check
  the beginning of a mark whenever smoothing is introduced.
- **When a rendering fault survives redesigning the renderer, the renderer is not the problem** — the
  input, or the frame the output is placed in, is. Three releases of grain work changed nothing the
  artist could see, because the grain was never wrong.
- **Inspect a texture at the size it will be looked at.** This defect is invisible at 10× pixel zoom,
  where one bristle reads as ordinary speckle, and obvious at 1×. Every wrong diagnosis in that
  sequence came from magnifying past the scale the defect lives at.
- **Noise that becomes SHAPE must be smoothed; noise that becomes TONE need not be.** A digitizer's
  tilt jitters several degrees sample to sample and a hand cannot roll a pen that fast — fed raw into
  a width it fringes both edges of the mark with fine hairs ("pipe cleaner"). `GraphiteGrain` averages
  the lean over ~40 px of arc, **causally**, so a prefix still renders like the whole stroke; pressure
  stays raw because it sets darkness and darkness noise reads as grain. Paintsprout's Wacom app has
  the identical fault (`tiltGain` off raw tilt) — **the shared symptom across two unrelated renderers
  is what identified the shared input.** When a rendering fault survives redesigning the renderer,
  suspect the input.
- **Graphite laid down as CONNECTED geometry looks like hair.** A grain fleck wider than the lattice
  that spaces it must touch its neighbours, and touching flecks become little worms a fleck thick and
  several long — half a millimetre of bristle at 300 dpi, which an artist called a "pipe cleaner".
  Paintsprout's Wacom app has the same fault from the other direction (its grain is continuous
  *lanes* along the stroke). The cure (0.1.14): size the fleck **against the pitch and ramp it by
  darkness** — about one pitch at the pale end so specks stand alone, over two at the dark end so
  they flood into solid ink. The panel's own charcoal, magnified, is essentially a one-pixel dither;
  nothing in it is connected.
- **The bake is `screencap`-visible even when live EPD ink is not**, and marks re-render from stored
  data on open — so a pixel-exact image of what the *current* renderer makes of an old drawing is one
  `adb exec-out screencap`, no camera needed. Reach for that before photographing anything.
- **An aggregate statistic cannot see structure — magnify the texture and compare it.** Mass, extent
  and coverage were all matching the panel's ink while our grain was plainly wrong: the flecks were
  combed into short dashes running *along* the stroke where the panel's speckle is isotropic. A
  fleck wider than the lattice pitch that spaces it fuses with its neighbour in the same lane, station
  after station, and the mark grows a direction graphite does not have. Fixed in 0.1.13 by sliding
  the comb sideways a random fraction of a lane at every station. **Any lattice-placed texture needs
  something that decorrelates it along the path**, or it will comb.
- **A combed texture does not look like an even scatter at the same coverage**, so a density
  judgement made over a structurally wrong grain is not worth acting on — fix the structure first,
  then re-judge density.
- **Scale a texture's pitch and its grain size by the same factor** and coverage, and therefore
  density, is untouched — which is what lets grain fineness be tuned independently of how dark a
  mark is.
- **A binary threshold cannot measure a texture whose nature is partial coverage.** Comparing a
  photographed live stroke against its bake, thresholding high enough to segment cleanly made the
  baked bands read ~30% narrower — the threshold was discarding pale outer flecks, and it inflated
  the coverage measured inside the band at the same time, so width and density were entangled and
  both wrong. Threshold-free statistics settle it: **total ink mass per unit length**, and the
  profile's second moment. At a threshold low enough to keep the pale flecks the extents matched
  within 4%.
- **`Stroke.width` means the width of the mark — protect that when a firmware disagrees.** BOOX's
  `CHARCOAL_V2` overdraws ~1.3× (measured NA5C, constant across pen angle; corrected in 0.1.12 by
  dividing the width `gpaper-onyx` hands `setStrokeWidth`). The live style and the bake are coupled
  through one `penWidth`, so only one of them can be corrected — **correct the engine, never widen
  the renderer.** Widening the bake to meet a firmware would make `Stroke.width` a per-device
  fiction, and a host compositing its own ink through `StrokeRasterizer` would get a different
  answer from the one on screen. It also costs the live ink nothing: a host that scales its pen
  widths to suit hands the firmware the same number as before.
- **When a correction is aimed at one axis, keep it flat across the others.** 0.1.11 raised grain
  density by a factor deliberately constant over the whole pressure range, because the light-to-hard
  response had already been approved — a density fix that also moved pressure would have undone a
  settled decision while appearing to fix something else. The same logic pins the upright width
  anchor when the tilt curve is retuned.
- **Measure the device before explaining it.** Phase 11 lost a round trip to an inference from
  `NoteConstant.CHARCOAL_STROKE_WIDTH_EXTRA_SCALE` — a constant BOOX's *own app* applies, reasoned
  from a finding about the NeoPen *software* renderers, a different code path from the firmware
  overlay — stated as if it had been measured. It lost another to writing off `CHARCOAL_V2` on a
  one-line survey description; that style turned out to be the one the artist wanted.
- **Pen-activity gate includes hover.** `isPenActive` = writing ∨ hovering + 350 ms tail — the palm
  lands before the pen tip, so proximity must close the gate. Traps: stylus hover is delivered
  to `onHoverEvent` first (pointer-source) — and since the paper view is not hoverable it returns
  false, so the platform then delivers the SAME MotionEvent to `onGenericMotionEvent` too. Handle
  both entries, but process pointer-source hover only on the hover leg (core's
  `isPointerSourceHover` predicate), or every sample is handled twice — and any per-event mutation
  (Ratta's `offsetLocation` registration shift) is applied twice; tap-like host
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
  input thread; the gate fields are volatile. **The bus is the ONLY safe level feed:** the SDK
  marshals the host-facing `onPenActive` callback through the view's Handler, so a backlog of
  posted reports can flush AFTER the raw-thread `PenDeactivateEvent` and re-latch the gate closed
  forever (measured NA5C — ~50 stale reports after the deactivate while main was busy completing a
  lasso; finger selection drag/dismiss stayed refused). While the bus is subscribed the callback
  must contribute nothing; it degrades to pulse-tail semantics only when the subscribe failed.
  `gpaper-onyx` ships `consumer-rules.pro` (EventBus `@Subscribe` keep rules) so minifying
  consumers can't strip the subscriber and lose the exit signal.
- **Ratta bake-handoff ordering (fourth overlay law, found here — not in the reference).** The
  deferred bake must issue `clearAll` **between** the node record and the `invalidate`
  (`RattaPaperView.redrawCommitted`): the daemon pairs a clear with the next app frame it sees, and
  a clear issued after the invalidate can pair with a stale in-flight frame recorded *before* the
  bake — the overlay drop then reconciles against stroke-less pixels and the just-written ink
  visibly vanishes until a later repaint damages the region. The reference never hit this because
  its hosts present no frames mid-writing; g-paper hosts MAY present frames at any moment (the
  demo's raw counter did at input rate until the Phase-9 frame-silence rule below deferred it),
  so the clear ladder also arms after **every** bake handoff — post-bake, every
  possible frame contains the strokes, making retry pairs harmless when the handoff landed and a
  ≤450 ms self-heal when it didn't.
- **Ownership guards are process-local; a cross-process handoff must DROP the token (0.1.2).**
  `penOwner` (Onyx) / `inkOwner` (Ratta) are statics — a successor paper screen in another
  process (Notesprout Paper's scratch-pad extension) can never overwrite them, so every
  later teardown of the departing view (focus loss, detach, `release()`) would still fire
  against the device-global pipeline, *after* the successor's claim. Onyx's
  `closeRawDrawingIfOwner` already nulls the owner + `isSetup` on `releaseForHandoff()`;
  Ratta's `releaseForHandoff()` now does the full teardown (overlay release, full-screen
  disable, `enableFullUiAuto(false)`) and nulls `inkOwner`. Symptom before the fix: the
  caller's session stayed live but the panel left full-UI-auto ≈ 200 ms after the reclaim
  → every drag frame on the slow waveform until a later re-arm ("sluggish drag", Nomad).
- **The Onyx fast-mode pin is per-session, not per-PEN (0.1.3).** `applyToolState` pins the
  app-scope handwriting waveform for every drawing tool, not only when PEN arms: a pipeline
  (re)opened on the LASSO (a host resuming after a cross-process handoff while a selection flow
  left it on the lasso) otherwise ran every drag frame unpinned — the "sluggish drag after a
  transfer" on a NoteAir5C, cured by any later PEN arming (dismiss → pen → reselect).
- **Pen-gesture recognizers are shared-base machinery too** (Phase 9): the geometry gates live
  once in pure-JVM `geometry/GestureRecognizer`, detection sits at the single commit point in
  `CanvasPaperView.commitCapturedStroke` (mid-contact exclusion fragments pass
  `allowGestures = false`), and engines contribute ONLY ink retraction via the
  `onGestureStrokeConsumed` seam (Onyx: render-off + dismissal repaint + retry at contact end —
  withheld-frame rules; Ratta: the gesture-trace clear ladder). Two hard-won classification facts:
  **scribble-shape screens the smart lasso and is exclusive** — real zigzag scribbles routinely
  satisfy the loop gates too (closure + winding; a spiky coil passing BOTH gate sets is pinned in
  a JVM test), so a dense oscillating stroke is never a lasso and an empty-hit scribble falls to
  ink, never to lasso; and **the winding gate cannot reject closed retraces** — nearly any closed
  path winds ≥360° around its own centroid (a flat hairpin is topologically a thin loop), so the
  empty-hit-test fallthrough is the real guard against loop-shaped false positives. Component-
  initiated tool changes (smart-lasso LASSO switch, PEN restore at session end) fire
  `PaperListener.onToolChanged`; the restore can land AFTER `onSelectionDismissed` (tap-away
  dismisses at pen-down, restores at pen-up) — never advise hosts to read `tool` in selection
  callbacks.
- **EPD frame-silence rule (Ratta-critical):** pixels under Supernote overlay ink are frozen
  against app updates, so every app frame presented mid-writing pays a masking cost that GROWS
  with the accumulated unbaked overlay ink — felt as progressively lagging ink (measured Nomad;
  Manta merely later). The reference never hit it (its hosts present no frames mid-writing).
  Hosts must present NO frames while `isPenActive` — and note the raw `HOVER` stream arrives at
  input rate on Ratta/generic (EMR pens hover between every stroke; on Onyx hover never reaches
  the view as MotionEvents), so chrome must never repaint from `MOVE` OR `HOVER` raw events. The
  demo's gate-deferred status line is the reference implementation; `dumpsys gfxinfo` is the
  measuring stick (1182 frames/65% janky in one short writing session before the fix, ~30–50
  per session after).
- **Selection/lasso is shared-base machinery** (`CanvasPaperView`): device engines add only trail
  chrome + EPD drag handling, driving the base's protected seams from their pipelines. Trails are
  engine chrome, never model data. The stylus-only contract has ONE exception: while a selection is
  active, a single finger drags it / a finger tap dismisses it (palm-gated: `isPenActive` refusal,
  mid-drag pen cancel, multi-touch kill, `PEN_ACTIVE_TAIL_MS` escrowed dismissal) — hosts with touch
  listeners on the paper view must yield finger events while a selection is active. Host content
  joins drags via the optional `ContentRenderer` pair (exclusion-aware `draw` + `drawObject`);
  implement both or neither.
- **Firmware geometry is captured, not tracked** — a resize (rotation, insets change) must
  re-push it: Onyx `setLimitRect` re-applies in `OnyxPaperView.onSizeChanged` (posted — mid-layout
  `getLocationOnScreen` lies), Ratta re-runs `setupFirmwareInk` for its screen-space disable areas.
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
- **Host content and the eraser (0.1.4):** the eraser tool *reports* swept content whole
  (`onContentErased`, per-gesture dedup in `CanvasPaperView.reportedContentErases`) — the component
  never removes host content itself; the host deletes + `notifyContentChanged()`. Scribble erase
  stays stroke-only by design. `StrokeRasterizer` (public, `core/render/`) is the offline door to
  the internal `StrokeRenderer` — hosts compositing their own content (Notesprout Paper link
  composites) draw through it so baked appearance stays pixel-identical; never suggest a host
  reimplement stroke drawing.
- **The two tap signals are complements (0.1.5).** `onSelectionTapped` owns every sub-threshold tap
  *inside* an active selection box; `onPaperTapped` owns the stylus tap in `Tool.LASSO` with
  *nothing* selected (the host's paste-here hook). Neither ever fires for the other's case, and the
  tap that **dismissed** a selection fires neither — `lassoOutlineStart` latches
  `outlineDismissedSelection`, because a contact spent on a dismissal must not also be read as an
  empty-handed tap. `onPaperTapped` needs no escrow: the finger path (`handleFingerSelection`) only
  ever drags or dismisses an *active* selection, so a contact reaching `completeLassoOutline` is a
  stylus by construction.

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
- **Publishing is mavenLocal-only** (Phase 6 decision): `./gradlew publishToMavenLocal` publishes
  `com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.5` + sources (coordinates in
  `gradle.properties`). `consumer-smoke/` is a standalone consumer project (NOT in the root build)
  proving a host app builds against the published artifacts:
  `./gradlew -p consumer-smoke assembleDebug` after copying `local.properties` in (see its README).
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
  the fact. Also a general BOOX trap (reproduced on NA5C and G102): `install -r` + immediate
  `am start` can race package finalization, leaving the package installed but **disabled**
  (`enabled=3`, "Activity class does not exist") — heal with `pm enable <pkg>`.
- BOOX has a real status bar overlaying the window top (Supernote has none) — host layouts must
  apply system-bar insets; the demo pads its root via `setOnApplyWindowInsetsListener`.

## Working style

- Deliberately slow and careful; frugal with background agents/tokens — work mostly inline.
- Each phase: plan → build → test → commit & push when green. Update `PLAN.md` statuses the moment
  they change; fold new durable knowledge into this file at phase close-out.
