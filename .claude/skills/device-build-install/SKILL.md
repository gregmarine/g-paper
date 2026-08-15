---
name: device-build-install
description: Build and install the g-paper demo app to physical BOOX/Supernote/tablet devices by nickname (G10, MAX, SNN, etc.); includes the gradle/adb commands and the full device-serial + tier table. Use whenever asked to build, install, or sideload the demo, or to look up a device's ADB serial.
---

## Build & Install

```sh
# Debug → demo/build/outputs/apk/debug/demo-debug.apk
./gradlew :demo:assembleDebug

adb -s <serial> install -r demo/build/outputs/apk/debug/demo-debug.apk

# Launch
adb -s <serial> shell am start -n com.symmetricalpalmtree.gpaper.demo/.MainActivity
```

Install all requested devices in a single shell block. If the user says devices are ready, **skip
`adb devices`** — go straight to build and install. Users refer to devices by nickname (e.g. "G10").

### Device Serials & Tiers

| Device | Serial | | Device | Serial |
|---|---|---|---|---|
| BOOX NoteAir5C (NA5C) | `92c16533` | | BOOX Go Color 7 (GC7) | `98d56306` |
| BOOX Note Max (MAX) | `6325773d` | | BOOX NoteAir4C (NA4C) | `1d36f870` |
| BOOX Go 10.3 (G10) | `34E517F9` | | BOOX Tab XC (TXC) | `d852bed0` |
| BOOX Go 6 Gen II (G6) | `DAF86F61` | | BOOX Go 7 (G7) | `17845014` |
| BOOX Palma2 Pro (P2P) | `287d2364` | | Wacom Movink Pad 11 (MIP11) | `5HL21V5007384` |
| BOOX Go 10.3 Gen 2 (G102) | `b7a46e13` | | Supernote Nomad (SNN) | `SN078D10012852` |
| Paper 7 (P7) | `T1737BBR0327` | | Supernote Manta (SNM) | `SN100C10023972` |
| Samsung Galaxy S26 Ultra (S26U) | `R3GL307HGDH` | | | |

> ⚠️ **The Supernote Manta reports itself as a Nomad.** Every `ro.product.*` property is identical
> across the two (`manufacturer=Supernote`, `model=Supernote Nomad`), and they run the same firmware
> build. **The serial is the only reliable way to tell them apart** — always pass `-s`, never trust
> `adb devices` model strings. On-device they differ only by resolution: Nomad 1404×1872, Manta
> 1920×2560, both at density 300. Note also that `Build.MANUFACTURER` is `"Supernote"`, **not**
> `"ratta"`.

- **Tier 1 (primary, always-tested):** Supernote Manta (**flagship**) & Nomad (gpaper-ratta
  firmware ink — install **both** for any Ratta work), BOOX Go 10.3 Gen 2, Go 6 Gen II, Note Max,
  Palma2 Pro, NoteAir5C (gpaper-onyx)
- **Tier 2 (QA):** BOOX NoteAir4C, Tab XC, Go Color 7 Gen II, Wacom Movink Pad 11 & 14 Pro
  (gpaper-core generic engine)
- **On hand, in no tier** — still installable on request, serials above: BOOX Go 10.3, Go 7,
  Paper 7 (generic engine), Samsung Galaxy S26 Ultra. No serial is recorded for the
  Movink Pad 14 Pro.

Remember: EPD pen overlays are invisible to screencap — the user verifies ink behavior by eye.
Non-ink app UI does appear in screencap, so app-launch checks over adb are fine.
