# g-paper consumer smoke test

Standalone Gradle project (deliberately **not** part of the root build) that consumes the
published g-paper artifacts the way a real host app would — from mavenLocal, with the
transitive Onyx SDK dependencies resolved through the published POMs.

```sh
# from the repo root
./gradlew publishToMavenLocal
cp local.properties consumer-smoke/local.properties   # SDK location (machine-specific, gitignored)
./gradlew -p consumer-smoke assembleDebug
```

A green build is the test: a broken published artifact (missing class, bad POM
dependency, mangled metadata) fails compilation or dexing. The APK is also installable —
it draws a full-screen paper surface with the auto-selected engine.

Because this consumer ships `gpaper-onyx`, its build carries the BOOX consumer
requirements (the insecure BOOX maven repo, jetifier, the `tools:replace` label override,
`libc++_shared.so` pickFirsts). A generic- or ratta-only consumer would need none of them
— which is the integration story this project exists to verify.
