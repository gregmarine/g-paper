# g-paper

An Android library that embeds a writable/drawable **paper** surface in any app. It captures pen
input and renders content — nothing more. The hosting app owns all data, persistence, gestures,
and app logic.

Extracted and redesigned from the [Notesprout](https://github.com/) drawing engines, with
first-class support for e-paper devices (BOOX, Supernote) alongside generic Android.

> **Status: pre-alpha, under construction.** See `PLAN.md` for the build plan and current phase.

## Modules

| Module | Purpose |
|---|---|
| `gpaper-core` | Public API + generic Canvas engine. Near-zero dependencies. |
| `gpaper-onyx` | BOOX (Onyx) adapter — firmware raw-drawing pipeline, EPD handling. |
| `gpaper-ratta` | Supernote (Ratta) adapter — firmware live ink + deferred bake. |
| `demo` | Demo app exercising the library on real devices. |

Apps add only the modules for the devices they target; `gpaper-core` alone covers generic Android.

## Philosophy

The component does not own content. It is paper: it captures pen input and renders what it is told
to render. Strokes are plain Kotlin data classes; the host persists them however it likes. Host
content participates through a renderer extension point.

## Building

Requires JDK 17 (Temurin, pinned via `org.gradle.java.home` in `gradle.properties`).

```sh
./gradlew build            # everything
./gradlew :demo:assembleDebug   # demo APK
```

## License

MIT — see `LICENSE`.
