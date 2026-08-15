# g-paper Architecture

How the library is built and why. The host-facing surface is documented in [api.md](api.md);
this page is for people working on g-paper itself or wanting to understand what the engines do
under the hood.

## Modules

```
gpaper-core    public API + generic Canvas engine     (near-zero deps)
   ▲       ▲
gpaper-onyx  gpaper-ratta                              (each `api`-depends on core)
BOOX adapter Supernote adapter
(Onyx SDK)   (zero deps — direct firmware Binder)

demo           demo app          consumer-smoke   standalone published-artifact consumer
```

Core never depends on the adapters. Adapters register `PaperEngineProvider`s with the `GPaper`
registry explicitly — no ServiceLoader, no reflection, R8-safe. `GPaper.create` picks the
highest-priority engine whose availability probe passes (device modules at priority 100, generic
at 0), once, at creation, logged at `Log.i`. **There is no runtime fallback**: an engine failure
after construction is loud, never a silent swap — a probe that fails at creation time is engine
*selection*, which is the only fall-through that exists.

## One shared base, thin device engines

The central design decision, made against the reference codebase's biggest defect: Notesprout
maintained its Ratta view as a hand-edited copy of its generic view, and the copies drifted.
In g-paper the whole canvas engine lives **once**, in `core/canvas/CanvasPaperView`, and both
device views subclass it:

```
CanvasPaperView (core)         ← the generic engine, and the base class
├── OnyxPaperView (gpaper-onyx)
└── RattaPaperView (gpaper-ratta)
```

The base owns: the stroke model working copy, committed-layer rendering, live-stroke drawing,
eraser sweep + hit testing, template/page geometry, host-content renderers, the pen-activity
gate, exclusion-rect filtering, and the **entire selection/drag state machine** (lasso outline
capture, tap-vs-outline classification, selection box, drag with hidden-strokes re-record and
translated drag layer, finger drag/dismiss with palm gating).

Device engines override protected seams only — where the ink pixels come from and how the EPD
panel is handed off. They contribute trail chrome and firmware plumbing, never model logic.
`StrokeRenderer` is the single source of truth for committed stroke appearance: live ink is a
per-engine best-effort preview; the baked stroke is the truth, identical on every engine.

`canvas/` is public **only** so the device modules can subclass it. It is never host API; hosts
go through `GPaper.create`.

## Purity split

`model/` and `geometry/` are pure Kotlin — no Android imports — which is what lets the unit
tests (72 at 0.1.0) run on the JVM with no Robolectric: stroke/bounds semantics, erase narrow
phase (segment-to-segment distance, so fast sweeps can't jump strokes between samples), lasso
hit testing (any-point-in-polygon for strokes, polygon-vs-rect for host content), and the Ratta
grey-threshold ink map. Android bridging lives in `model/AndroidInterop.kt`.

## Rendering model (base engine)

- **Committed layer** — template + host content + baked strokes, recorded into a hardware
  `RenderNode` display list. Re-recorded only when content mutates (commit, erase, load,
  template/renderer change), never per frame; `onDraw` blits it. A software-canvas fallback
  draws the vectors directly (EPD repaint paths run software canvases).
- **Live layer** — the in-progress stroke, drawn in `onDraw` at input rate through the same
  `StrokeRenderer` as the bake.
- Erase and lasso sweeps throttle redraws to 60 ms; nothing re-tessellates per stroke per
  frame.

Input is stylus-only. Finger events are never consumed (host gestures work above the paper),
with one narrow exception: while a lasso selection is active, a single finger can drag it and a
finger tap dismisses it, palm-gated.

## The Onyx engine (BOOX)

Live ink is painted by the firmware through the Onyx SDK's raw-drawing pipeline
(`TouchHelper` → `EpdController`); everything else is the base. The ported-and-improved device
knowledge:

- **First-stroke fast-mode pin** — an app-scope handwriting waveform removes the 1–2 s
  first-stroke lag. It is registered with the EPD service *by name, not process*, so it
  survives process death; `OnyxEngine.register` heals leaked state at every process start.
- **Process-global pen ownership** — one view owns the raw pipeline; Android runs the incoming
  screen's open before the outgoing screen's close, so every close checks ownership.
- **Overlay handoffs** — disabling raw rendering does not clear the hardware buffer; every
  content swap runs a render-off → repaint → `handwritingRepaint` → re-arm dance, coalesced
  when several land in one frame. Erase repaints only at gesture end (per-move would flash the
  panel).
- **Withheld frames** — frames presented during a live raw contact never reach the panel, and
  a damage-free pen-up invalidate doesn't repaint. Overlay-chrome changes made at pen-down
  (e.g. tap-away selection dismissal) need an explicit `handwritingRepaint` at contact end plus
  a ~250 ms retry; the lasso trail arms at the first *move* sample, never pen-down.
- **Pen proximity** — BOOX emits no pen-approach signal at all until the SDK's post-input
  master switch is on; with it on, the engine subscribes to the SDK event bus for
  pen-active/deactivate events (enter/leave EMR range) feeding the shared palm gate. The bus is
  the only safe feed: the host-facing callback is Handler-marshalled and a stale backlog can
  re-latch the gate after the real deactivate.
- Live style mapping to the nine firmware styles is device-proven no-restart and
  fast-mode-safe; tilt is deliberately captured as 0 (per-device firmware scales, no
  normalizer).

## The Ratta engine (Supernote)

Live strokes are painted by the firmware's ink daemon on the EPDC overlay, driven by
`SupernoteInk` — a Binder client speaking raw `Parcel` transactions to the firmware service.
No SDK exists; this is why the module has zero dependencies. The firmware returns **no point
data** — points arrive as ordinary MotionEvents, so capture is exactly the base engine's.

- **Deferred bake** — finished strokes enter the model at pen-up, but the visual bake waits
  for a natural boundary (tool switch, chrome, page turn); baking per lift fights the hardware
  (flash + ghosting). The overlay keeps showing firmware ink until the bake clears it.
- **The four overlay laws** — (1) a `clearAll` reconciles nothing without a co-presented app
  frame; (2) clears near pen-lift/pen-down can be eaten by the daemon's stroke-finalization
  window — remedied by a retry ladder (≤450 ms self-heal); (3) the firmware latches pen state
  at contact start, so suppress/disable must be issued from the **hover** stream, before the
  tip lands; (4) the bake handoff must issue `clearAll` *between* recording the committed node
  and invalidating, or the clear pairs with a stale in-flight frame and the just-written ink
  vanishes (found in g-paper — its hosts may present frames at input rate; the reference never
  did).
- **Disable areas are screen-space** and the only "firmware off" switch: complement bands
  around the view plus the host's exclusion rects; re-pushed on every resize.
- **Registration compensation** — the MotionEvent stream lands slightly left of the physical
  tip; a +2 px (Nomad) / +3 px (Manta) shift is applied at input entry, branched on screen size
  because the Manta reports itself as a Nomad in every build property.
- Process-global ink ownership mirrors the Onyx guard; a pen-approach re-arm recovers sessions
  the daemon silently dropped during window transitions.

## Palm rejection (shared gate)

`isPenActive` = writing ∨ hovering, plus a 350 ms tail — the palm lands before the pen tip, so
proximity must close the gate. Feeds differ per engine (MotionEvent hover on generic/Ratta, the
SDK bus on Onyx) but the gate and its contract live in the base. Hosts gate finger gestures on
it, re-check at finger-up for tap-like gestures, and escrow tap-*actions* for the tail duration
— a palm micro-tap can complete ~190 ms before the pen enters hover range, invisible to any
proximity signal at up-time. Contact size is no substitute: EPD touch panels may report no
contact geometry at all.

## Firmware geometry is captured, not tracked

Anything pushed to firmware in screen coordinates (Onyx limit rect, Ratta disable areas) is a
snapshot — a resize (rotation, insets change) re-pushes it from `onSizeChanged`.

## Testing strategy

- **JVM tests** cover everything pure: geometry, hit tests, model semantics, ink-map
  thresholds. `./gradlew build` runs them.
- **On-device, eyes-on** covers everything else — EPD pen overlays are invisible to screencap,
  and adb input injection can't synthesize stylus tool types, so ink behavior is verified by
  a human on real BOOX/Supernote hardware. Each engine phase carried a device checklist
  (first-stroke latency, styles live+baked, erase handoffs, palm gate, selection, app-switch
  release); the histories live in `PLAN.md`.
