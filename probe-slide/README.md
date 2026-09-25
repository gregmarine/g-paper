# probe-slide — what the Supernote's side bars send an app

A from-scratch, dependency-free app that logs every `KeyEvent` an activity receives, names the
side-bar codes (right bar 310 / 309, left bar 301 / 300, `DRAG` 290, 291, `SLIDE` 292, `MENU` 82),
summarises each bar burst with its key codes in arrival order, logs any touch from a device
other than the main panel, receives the firmware launcher's decision broadcasts
(`…launcher.flashscreen` = swipe up, `…launcher.slidebarstatusbarstate` = side menu / status
bar), and drives the launcher's exported `GestureService` binder from buttons: **Bind**, **Lock /
Unlock (binder)** (transactions 4 + 5), **Menu (binder 82)** (transaction 3). The **Lock / Unlock
(bcast)** buttons exist to show that the Notes app's lock broadcast is protected and refused.

    ./gradlew :probe-slide:assembleDebug
    adb -s SN078D10012852 install -r probe-slide/build/outputs/apk/debug/probe-slide-debug.apk
    adb -s SN078D10012852 shell am start -n com.symmetricalpalmtree.gpaper.probeslide/.MainActivity
    adb -s SN078D10012852 logcat -s SlideProbe

For the raw kernel stream beside it (shell may read the device; apps may not):

    adb -s SN078D10012852 shell "timeout 300 getevent -lt /dev/input/event6"

Everything it found — the two keys per bar, no position, no direction, the finger count,
the chord, the tap / double-tap / hold timings, the protected broadcast, the working binder,
the lock's lifetime — is written up in `../launcher-demo/README.md`.
