# g-paper

An Android library that embeds a writable/drawable **paper** surface in any app. It captures pen
input and renders content — nothing more. The hosting app owns all data, persistence, gestures,
and app logic.

First-class support for e-paper writing devices — **BOOX (Onyx)** and **Supernote (Ratta)** get
firmware-accelerated live ink at sub-frame latency — alongside any generic Android device, all
behind one API. Extracted and redesigned from the Notesprout drawing engines.

**Version 0.1.3.**

## What you get

- **Stroke capture & rendering** — stylus-only input; strokes are plain Kotlin data classes
  (id, points with x/y/pressure/tilt/time, color, width, style) with no serialization opinions.
- **Eight abstract pen styles** (`PEN`, `FOUNTAIN`, `MARKER`, `PENCIL`, `BRUSH`, `CALLIGRAPHY`,
  `DASH`, `CROSS`) — committed appearance is core-rendered and portable across devices; live ink
  maps to the nearest native firmware style per engine.
- **Eraser** — whole-stroke with a radius, segment-accurate hit testing, batched callbacks.
- **Lasso selection & drag** — outline capture, selection box, pen or single-finger drag,
  host-content participation; the component does the EPD-tricky mechanics, the host applies the
  resulting move to its data.
- **Pen-gesture recognizers (opt-in)** — smart lasso (a quick closed pen loop around content
  selects it) and scribble erase (a dense zigzag over strokes erases them); both default off,
  pen-tool only, and fall through to ordinary ink when they catch nothing.
- **Templates & page geometry** — background bitmap stretched into the authored page rect, so
  ink/template registration survives moving data between different-sized screens.
- **Host content extension point** — renderers draw into the committed layer, z-ordered relative
  to ink, with hit bounds that opt objects into selection.
- **Palm rejection** — a pen-activity gate (`isPenActive`: writing ∨ hovering + tail) the host
  uses to gate its finger gestures; fed by hover on generic/Ratta and the SDK pen-approach bus
  on BOOX.
- **Raw input passthrough** — observe the stylus stream regardless of active tool.

## Modules

| Module | Purpose |
|---|---|
| `gpaper-core` | Public API + generic Canvas engine. Near-zero dependencies. |
| `gpaper-onyx` | BOOX (Onyx) adapter — SDK raw-drawing pipeline, EPD handling. |
| `gpaper-ratta` | Supernote (Ratta) adapter — direct firmware ink binder, **zero added dependencies**. |
| `demo` | Demo app exercising the library on real devices. |

Apps add only the modules for the devices they target; `gpaper-core` alone covers generic
Android. `gpaper-onyx` brings the BOOX SDK's build baggage (see the
[integration guide](docs/integration-guide.md)); `gpaper-ratta` brings nothing at all.

## Quickstart

Publish to mavenLocal (0.1.3 is mavenLocal-only), add `mavenLocal()` to your repositories, then:

```kotlin
dependencies {
    implementation("com.symmetricalpalmtree.gpaper:gpaper-core:0.1.3")
    implementation("com.symmetricalpalmtree.gpaper:gpaper-onyx:0.1.3")  // only if you target BOOX
    implementation("com.symmetricalpalmtree.gpaper:gpaper-ratta:0.1.3") // only if you target Supernote
}
```

```kotlin
// Application.onCreate — register only the device modules you ship (generic is built in)
OnyxEngine.register(this)   // BOOX
RattaEngine.register()      // Supernote

// In the hosting screen
val paper: PaperView = GPaper.create(context)   // best available engine, logged at Log.i
frame.addView(paper.asView())                   // chrome overlays it in a FrameLayout

paper.setPaperListener(object : PaperListener {
    override fun onStrokeCommitted(stroke: Stroke) = db.save(stroke)
    override fun onStrokesErased(ids: List<String>) = db.delete(ids)
})
paper.loadStrokes(db.loadStrokes())

// Lifecycle
override fun onResume() { super.onResume(); paper.resumeDrawing() }
override fun onDestroy() { paper.release(); super.onDestroy() }
```

## Philosophy

The component does not own content. It is paper: it captures pen input and renders what it is
told to render. Heavy lifting — persistence, pages, undo/redo, gestures — stays in the host,
which mirrors every change out of the callbacks into its own storage, keyed by stroke id.

## Documentation

| Doc | What it covers |
|---|---|
| [docs/api.md](docs/api.md) | The public API, guided tour — data model, tools, styles, selection, events |
| [docs/integration-guide.md](docs/integration-guide.md) | Per-device build setup, registration, lifecycle wiring |
| [docs/architecture.md](docs/architecture.md) | Module design, rendering model, the EPD engine internals |
| [docs/host-responsibilities.md](docs/host-responsibilities.md) | What the host owns: persistence, pages, undo/redo, gestures, chrome |

## Building from source

Requires JDK 17 (Temurin; the path is pinned via `org.gradle.java.home` in `gradle.properties` —
adjust for your machine).

```sh
./gradlew build                  # everything: libraries, demo, JVM tests
./gradlew :demo:assembleDebug    # demo APK → demo/build/outputs/apk/debug/
./gradlew publishToMavenLocal    # publish the three library artifacts + sources
```

`consumer-smoke/` is a standalone project (not in the root build) that builds a real host app
against the published artifacts — see its README.

The demo app runs on anything (the generic engine's ink is ordinary View rendering); on BOOX and
Supernote hardware it exercises the firmware pipelines. Note that on e-paper devices the pen
overlays are invisible to screencap — ink behavior is verified by eye on the panel.

## License

MIT — see `LICENSE`.
