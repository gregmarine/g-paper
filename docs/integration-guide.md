# g-paper Integration Guide

How to build a host app against g-paper, per target device family. The API itself is in
[api.md](api.md); what the host is expected to own is in
[host-responsibilities.md](host-responsibilities.md). A complete, committed, working example of
everything on this page is the `consumer-smoke/` project at the repo root.

## Getting the artifacts

Publishing is **mavenLocal-only** for 0.1.2. From a g-paper checkout:

```sh
./gradlew publishToMavenLocal
```

This publishes three artifacts (plus sources jars), coordinates centralized in the repo's
`gradle.properties`:

```
com.symmetricalpalmtree.gpaper:gpaper-core:0.1.2
com.symmetricalpalmtree.gpaper:gpaper-onyx:0.1.2
com.symmetricalpalmtree.gpaper:gpaper-ratta:0.1.2
```

In the consuming app's `settings.gradle.kts`, add `mavenLocal()` to the dependency
repositories. Toolchain floor: minSdk 29, Java/Kotlin target 17.

## Which modules do I need?

| Target devices | Modules | Extra build setup |
|---|---|---|
| Generic Android only (phones, tablets, emulator) | `gpaper-core` | none |
| + Supernote (Ratta) | + `gpaper-ratta` | **none** — zero added dependencies |
| + BOOX (Onyx) | + `gpaper-onyx` | BOOX maven repo, jetifier, manifest label override, native-lib packaging (below) |

The adapters depend on core (`api` scope), so adding an adapter pulls core automatically.
Shipping all three in one APK is fine — engine selection is per-device at runtime, and
registering an adapter on foreign hardware is harmless.

## Generic-only consumer

```kotlin
dependencies {
    implementation("com.symmetricalpalmtree.gpaper:gpaper-core:0.1.2")
}
```

That's everything. No registration call needed — the generic engine is built in.

## Supernote (Ratta) consumer

```kotlin
dependencies {
    implementation("com.symmetricalpalmtree.gpaper:gpaper-ratta:0.1.2")
}
```

```kotlin
// Application.onCreate (or any point before the first GPaper.create)
RattaEngine.register()
```

No extra repo, no jetifier, no SDK — the module drives the Supernote firmware's ink daemon
directly over Binder. The engine's availability probe requires Supernote hardware **and** a
reachable firmware ink binder; absent either, `GPaper.create` falls through to the next engine,
so the same APK runs everywhere.

## BOOX (Onyx) consumer

This module carries the BOOX SDK, and the SDK carries build baggage. Four pieces:

**1. Repository** — the POM's Onyx SDK dependencies resolve from the BOOX repo, which has no
https mirror. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
    }
}
```

**2. Jetifier** — the Onyx SDK AARs bundle pre-AndroidX support classes. In
`gradle.properties`:

```properties
android.useAndroidX=true
android.enableJetifier=true
```

**3. Manifest label override** — the Onyx SDK AAR manifests carry their own application label,
which collides with yours at manifest merge:

```xml
<application
    android:label="My App"
    tools:replace="android:label">
```

**4. Native-lib packaging** — the SDK ships colliding `libc++_shared.so` copies across its
AARs, and its x86_64 libs are not 16 KB-aligned. In the app module's `build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }   // BOOX devices are arm64; also drops the bad x86_64 libs
    }
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
    }
}
```

Then the dependency and the registration call:

```kotlin
dependencies {
    implementation("com.symmetricalpalmtree.gpaper:gpaper-onyx:0.1.2")
}
```

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OnyxEngine.register(this)
    }
}
```

`OnyxEngine.register` **must** run from `Application.onCreate` and takes the `Application` for
a reason: besides registering the engine, it installs the hidden-API bypass the SDK needs on
Android 14+, and it heals EPD state (fast-mode pin, shortened refresh list) leaked by a process
killed mid-pen-session — leaked state is keyed by name, not process, and would otherwise ghost
the whole panel until reboot. All of that is wrapped; the host makes the one call.

Minifying hosts need no extra ProGuard/R8 work — `gpaper-onyx` ships consumer rules keeping the
EventBus subscriber the palm-rejection gate depends on.

**BOOX layout note:** BOOX devices have a real status bar overlaying the window top (Supernote
has none). Apply system-bar insets to your layout (`setOnApplyWindowInsetsListener` on the
root), or your toolbar sits under the status bar.

## Wiring the view

```kotlin
val paper: PaperView = GPaper.create(context)   // highest-priority available engine
frame.addView(paper.asView())                   // a FrameLayout; chrome views go on top
```

Engine choice happens once, at creation, and is logged at `Log.i`. There is no runtime
fallback: post-construction engine failures are loud, never a silent engine swap.
`GPaper.create(context, "onyx")` / `"ratta"` / `"generic"` forces an engine, bypassing the
availability probe (useful in development).

Lifecycle hooks the host must call:

| Host moment | Call |
|---|---|
| `onResume` | `paper.resumeDrawing()` |
| Immediately before launching **another** paper-hosting screen | `paper.releaseForHandoff()` |
| Immediately before `finish()`-ing **back to** a paper-hosting caller (same call, other direction) | `paper.releaseForHandoff()` — the caller reclaims in its `onResume`, which runs *before* this window's visibility change would close the pipeline; a close landing after the caller's reclaim tears the caller's live session down (BOOX: ink / lasso trails invisible until a tool flip; seen cross-process, Notesprout Paper arc 6) |
| `onDestroy` | `paper.release()` |

Chrome cooperation (all no-ops where they don't apply, so call unconditionally):

- `setExclusionRects(rects)` whenever chrome overlaying the paper opens/closes/moves — the
  stylus must not ink under a toolbar or menu.
- `releaseRender()` on finger interaction with chrome overlaying the paper, so an e-ink panel
  actually shows the UI change; it re-arms on the next pen-down.
- Gate finger-gesture handlers on `paper.isPenActive` — see
  [host-responsibilities.md](host-responsibilities.md) for the full palm-rejection contract.

## Verifying the integration

Build and run the `consumer-smoke/` project against your published artifacts:

```sh
./gradlew publishToMavenLocal
cp local.properties consumer-smoke/local.properties
./gradlew -p consumer-smoke assembleDebug
```

On-device notes: e-paper pen overlays are invisible to screencap, so verify ink by eye on real
hardware. The generic engine's ink is ordinary View rendering and shows up everywhere —
including on e-paper devices when no device adapter is registered.
