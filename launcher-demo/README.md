# launcher-demo — owning the Supernote's side bars and home screen from an ordinary app

A from-scratch, dependency-free app (nothing from g-paper) that proves, on a stock Supernote
Nomad (beta firmware 2.26.x, Android 11), that an unprivileged app can:

1. be the device's **HOME** screen;
2. read the **side-bar gestures in every app**, system-wide;
3. keep the firmware's own side menu **shut** and put its **own menu** up instead, over
   whatever app is in front — including Ratta's own apps and Notesprout.

Nothing of Ratta's is disabled or modified. Everything is reversible with two adb commands.
All of it was walked by hand on the Nomad on 2026-09-24. The sibling `probe-slide` module is the
raw-key probe the survey was built on.

    ./gradlew :launcher-demo:assembleDebug
    adb -s SN078D10012852 install -r launcher-demo/build/outputs/apk/debug/launcher-demo-debug.apk
    adb -s SN078D10012852 shell pm set-home-activity com.symmetricalpalmtree.gpaper.launcherdemo/.HomeActivity
    adb -s SN078D10012852 shell settings put secure enabled_accessibility_services com.symmetricalpalmtree.gpaper.launcherdemo/.BarService
    adb -s SN078D10012852 shell settings put secure accessibility_enabled 1

`adb logcat -s LauncherDemo LauncherDemoBars SystemUiManager GMX-GestureService` shows the whole dance.

**Back to stock:**

    adb -s SN078D10012852 shell settings put secure enabled_accessibility_services ""
    adb -s SN078D10012852 shell pm set-home-activity com.ratta.supernote.background/.MainActivity

## How the Supernote's side bars actually work (decompiled from the firmware)

Sources: `SupernoteLauncher.apk` (`com.ratta.supernote.launcher`, system_ext, platform-signed,
uid 1000), `SupernoteBackground.apk` (`com.ratta.supernote.background`), `services.jar`
(`PhoneWindowManager`), `framework.jar` (`KeyEvent`). Decompiled with jadx.

- **The bars are a keyboard.** The kernel input device `ratta-slide` (`/dev/input/event6`)
  emits Linux keys that `/system/usr/keylayout/ratta-slide.kl` maps to Ratta's private Android
  key codes **`KEYCODE_F13`..`F30` = 293..310** (Ratta added them to `KeyEvent`; also
  `KEYCODE_DRAG` 290 for the top-edge pull-down, 291 refresh, `KEYCODE_SLIDE` 292).
- **Each bar is two keys.** One key is held DOWN for as long as a finger is on the bar and
  released on lift; a second finger holds a second key. Right bar: **310** first contact,
  **309** second. Left bar: **301** first, **300** second. A third finger adds nothing.
  Android auto-repeat runs while a key is held, so the repeat count is hold duration.
- **The keys carry no position and no direction.** Position lives in a root-only sysfs node
  (`/sys/devices/platform/fe5d0000.i2c/i2c-4/4-0028/l_x` and `r_x`, the cyttsp5 driver) that
  Ratta's launcher polls while the key is down. `/dev/input/event5` `cyttsp5_mt` is registered
  as a second touchscreen but emits nothing during a bar swipe. Shell cannot even stat the
  sysfs node, so no app ever reads position.
- **`system_server` forwards every key to Ratta's launcher.** `PhoneWindowManager` binds, by a
  hard-coded component name, `com.ratta.supernote.launcher/.service.GestureService` and calls
  `IGestureInterface.onKeyEvent` for each key in `interceptKeyBeforeDispatching`, then returns
  0 for the bar keys — so they are **also dispatched to the focused app window**. The launcher's
  `SlipWatcher` turns the key stream plus the sysfs position into click / move / fast-move /
  long-press; in right-hand portrait mode the right bar's slide one way is `showSlideBar()` (the
  side menu) and the other way `refreshScreen()`; the left bar is ignored. There is no user
  setting, no config file, and no way to be that component without Ratta's platform signature.
- **The side menu and the pull-down status bar are `SYSTEM_ALERT_WINDOW` overlays** the launcher
  adds. They are gated by two static flags, `SystemUiManager.slideBarEnable` /
  `statusBarEnable`.
- **Ratta has no home screen.** The HOME resolver `com.ratta.supernote.background/.MainActivity`
  (priority −100) is a boot screen — logo, "start system" — that starts the handwriting daemon
  and the gesture service. The gesture service then opens its remembered `lastPackage`
  (default `com.ratta.supernote.note`) on top. The visible "home" on a Supernote is the Notes
  app's file browser. Launching Ratta's HOME by hand just brings the last-used app back.

## The doors an ordinary app has (all walked)

| Door | Result |
|---|---|
| Receive the bar keys in the focused activity's `dispatchKeyEvent` | ✅ works (`probe-slide`) |
| Send the launcher's lock broadcast `…BroadcastReceiver.slidebarstatusbar` (what the Notes app uses) | ⛔ **protected broadcast** — `SecurityException` from an app and from adb shell |
| Bind the launcher's **exported** `GestureService` and call its binder | ✅ works, no caller check: descriptor `com.ratta.supernote.launcher.IGestureInterface`, raw transactions **4 = lockSlidebar(int)**, **5 = lockStatusbar(int)**, 3 = onFirstKeyCode(int; 82 opens the side menu), 2 = onKeyEvent(KeyEvent), 6 = sendAppChanged(String, String). `lockSlidebar(true)` also removes whatever overlay is showing. |
| Be HOME with `pm set-home-activity` | ✅ works — and merely installing a HOME app already makes it the default (default priority 0 beats Ratta's −100) |
| An **AccessibilityService** with `flagRequestFilterKeyEvents` receiving the bar keys in every app | ✅ **works** — the system-wide door. Its menu is a `TYPE_ACCESSIBILITY_OVERLAY` (no overlay permission needed) |
| Read `/dev/input` or the sysfs position directly | ⛔ SELinux (`input_device` neverallow; sysfs root-only) |
| Replace the `system_server` → `GestureService` binding | ⛔ hard-coded package + class name, platform signature |

Two facts about the binder lock that shape everything:

- **It is cleared on every foreground change, in both directions.** The framework tells the
  launcher about each switch (`sendAppChanged`) and `GestureService.appChanged` calls
  `SystemUiManager.enable(true)` whenever the top package differs from the last one. A lock is
  not restored when the locking app comes back.
- **It is global and per-flag.** Lock only the side-menu flag (4); locking the status-bar flag
  (5) takes away the top-edge pull-down for everyone.

Direction, the one thing the keys lack, comes only from the firmware's own decisions, which it
broadcasts (receivable by anyone; only sending is protected):
`com.ratta.supernote.launcher.flashscreen` = right bar swipe **up** (the refresh — it fires and
flashes the panel even while the menu is locked), `com.ratta.supernote.launcher.slidebarstatusbarstate`
with extra `show` = right bar swipe **down** (silent while locked). The same `slidebarstatusbarstate`
broadcast is also sent for the pull-down status bar, so it is a side-menu signal only if the
right bar was touched in the last ~1.5 s. The left bar has no direction signal at all.

**Trap:** `adb shell input keyevent` reaches `PhoneWindowManager` (and so the launcher) but
**never reaches the accessibility key filter** on this build. Only a real swipe proves anything
about the service.

## The gesture vocabulary an app gets, per bar (walked with `probe-slide` and raw `getevent`)

tap (~100–350 ms) · double-tap (two clean DOWN/UP ~140 ms apart) · hold of any length, duration
measurable · one vs two-plus contacts (the second key lands within 1 ms of the second finger) ·
chord across both bars (independent DOWN/UP on 310 and 301 at once). Top / middle / bottom of a
bar give the same key — the second key is a second contact, not a zone. A 5 s hold emits nothing
beyond auto-repeat. Bare taps and holds never trigger the launcher; only a slide past its
distance threshold does. No position, no direction.

## What this app does

**`HomeActivity`** — a plain HOME + LAUNCHER activity: a page of text, a log, and its own
right-edge menu. It steps aside completely while `BarService` runs (`BarService.running`).

**`BarService`** — the system-wide bar owner, an `AccessibilityService` declared with
`flagRequestFilterKeyEvents` and `canRequestFilterKeyEvents` (`res/xml/bar_service.xml`):

- binds the launcher's `GestureService` on connect, releases the status-bar flag (never ours),
  and locks the side-menu flag;
- **observes** the bar keys in `onKeyEvent` and returns `false` — consuming them would blind the
  firmware and lose the refresh broadcast, the only "up" signal;
- on the right bar's first key (310): locks on DOWN (the launcher logs `showslidebar locked`
  ~260 ms into the swipe), and classifies on UP + 150 ms — under 250 ms is a tap, ignored;
  refresh broadcast seen during the press is swipe up, ignored; otherwise swipe down → its menu;
- re-locks 300 ms and 1200 ms after every `TYPE_WINDOW_STATE_CHANGED` package change (landing
  after the launcher's own re-enable);
- treats a `slidebarstatusbarstate show=true` as a side-menu leak (lock again, take over) only
  when the right bar was touched within 1.5 s — otherwise it is the status bar, left alone;
- shows the menu as a full-screen `TYPE_ACCESSIBILITY_OVERLAY` (`FLAG_NOT_FOCUSABLE`, so the app
  underneath keeps keyboard focus): scrim to dismiss, rows for Notesprout Dev, Home, Supernote
  Notes, Close;
- **after boot** (first 3 minutes of `elapsedRealtime`): a Notes arrival the user did not launch
  from the menu is Ratta's boot push — the service starts HOME again 300 ms later.

## What the fresh boot looks like (walked 2026-09-24)

| After boot | Event |
|---|---|
| +0 s | our HOME starts (the system's HOME) |
| +0.4 s | the accessibility service reconnects on its own — the secure setting persists |
| +4 s | binder connected, side menu locked |
| +15 s | Ratta's gesture service (started by `system_server`'s bind, not by Ratta's HOME) runs its own boot routine: the unlock screen, then `lastPackage` (Notes) **on top of our home** |

`lastPackage` is written only for four whitelisted Ratta apps (Notes, Document, WeRead, Atelier)
into the launcher's private prefs, so it cannot be steered to us; hence the boot-window
re-assert above. Both `set-home-activity` and the accessibility setting survive a reboot.

## What this implies for a real launcher

A Notesprout launcher = a home screen + this service. It owns the bars and the menu in every
app; Notesprout itself needs no menu code; nothing of Ratta's has to be disabled. What stays
the firmware's: the swipe-up refresh with its flash, and the top-edge pull-down status bar
(both could be taken over the same way — the pull-down is `KEYCODE_DRAG` 290 — if that day
comes). The alternative of consuming the bar keys in the service would make the binder lock
unnecessary and the firmware menu impossible, at the cost of all direction (the right bar
becomes a one-direction bar with tap / double-tap / hold / two-finger variants).

Disabling Ratta's launcher package (`pm disable-user --user 0 com.ratta.supernote.launcher`,
reversible with `pm enable`) would remove its menu, status bar, shutdown dialog, file
migration and update services outright; untested, and unnecessary given the above.
