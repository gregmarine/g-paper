# probe-ebc — a hand-driven probe of Supernote's panel driver

A from-scratch, dependency-free app (nothing from g-paper) that opens `/dev/ebc` from an
ordinary `untrusted_app` process and drives raw ioctls one button at a time. It exists to
answer, on real hardware, what an unprivileged app may do with the panel directly — the
path Supernote's own Atelier uses for its flash-free 16-grey pencils (Atelier runs as
uid 1000 `system_app`; the vendor policy line `allow appdomain rga_device chr_file {open
ioctl map …}` and `/dev/ebc`'s `rga_device` label are what let us in).

Build + run hands-free (Nomad):

    ./gradlew :probe-ebc:assembleDebug
    adb -s <serial> install -r probe-ebc/build/outputs/apk/debug/probe-ebc-debug.apk
    adb -s <serial> shell am start -n com.symmetricalpalmtree.gpaper.probe/.MainActivity --ez auto true
    adb -s <serial> logcat -d -s EbcProbe:I

## Request numbers (read out of `/system/lib64/libeinkutils.so`, firmware Chauvet.E103…2389)

| request      | name                 | seen in            | note |
|--------------|----------------------|--------------------|------|
| `0x48545201` | `'R',1` (unnamed)    | `initEbc`          | first call after `open`; 24-byte out-struct; `mmap(len = 5 × word[3])` follows |
| `0x48544206` | `HTEINK_IOC_GETCFG`  | `updateSFSettings` | 132-byte `hteink_config` out-struct |
| `0x48545701` | `HTEINK_IOC_DISPAREA`| `postEink*Rect*`   | -F / -H / -R variants — the display call |
| `0x48545801` | `HTEINK_IOC_MISCCTL` | `postEinkPWRect*`  | `CLERA_PW_RECT`, `HTEINK_MISC_SYNCWIN` |

`open("/dev/ebc", O_RDWR|O_CLOEXEC)`. The `0x45424349` magic seen beside these calls tags
the library's in-process context object, not a kernel struct.

## Result — Nomad, 2026-09-18, `u:r:untrusted_app:s0`

Both reads succeed. `R1` returned (little-endian int32s):

    [0] 0x001f0408   [1] 0x057c0750 = 1404<<16 | 1872   [2] 1872
    [3] 2628288 = 1872 × 1404 (one 8-bit grey frame)   [4] 0x0686012c (low 16 = 300 ppi)   [5] 0x06c00006

so the driver's framebuffer is 8 bpp grey, panel-sized, and `initEbc` maps five of them.
`GETCFG` returned a version word `0x00010002` followed by byte ramps `04 04 08 08 … 3c 3c`
(grey-level lookup tables; see `get_config_tbl` / `rgba888_to_gray8bitx_functions`).

## Painting — what the panel and the driver's memory told us (Nomad, 2026-09-18)

`R1` is `HTEINK_IOC_GETINFO` (the kernel logs its name). `mmap(fd, 5 × frameBytes, 0)` works
from `untrusted_app`. `DISPAREA`'s argument, from `postEinkHostBmpRectFast` / `postEinkPWRectFast`:

    struct { int32 left, top, right, bottom;   // PANEL coordinates
             int32 bufOffset;                  // byte offset of the source frame in the mapping
             uint8 mode;                       // host path 4 / 7 / 8 / 9 (app modes 3 / 4–14 / 15 / 16)
             uint8 flag;                       // host path 0; pen-write path 1 or 5
             uint8 pad[2]; }                   // 24 bytes

Pixels are one byte each holding a 4-bit grey, `0x00` black … `0x0f` white; the driver keeps
flags in the upper bits (`0x40`, `0x80` seen mid-compose) — write plain 0–15.

**Sixteen bands of grey, frame 0, mode 7: drawn on the panel, cleanly, no flash, no bake**
(the user's eyes, twice). Modes 4 / 8 / 9 draw something else with the same data (shrunken or
shifted copies of the pattern) — not decoded; mode 7 is the 16-grey one.

The five frames:

| frame | what it is |
|---|---|
| 0 | the compositor's output — the current screen, rewritten by the HWC every ~0.2–1.4 s **whether or not we call DISPAREA** |
| 1 | the firmware pen daemon's ink overlay: **1-bit**, `0x00` ink / `0xff` clear (your sketch strokes were still in it) |
| 2 | a copy of the last displayed image (double buffer / shadow of 0) |
| 3, 4 | RGA scratch — zeroed by the system within ~1.4 s of a write |

So nothing in the mapping is ours to keep. The Atelier model follows from that: **paint direct
for latency, then draw the same pixels into your own window** so the compositor's periodic
rewrite changes nothing the eye can see. For that mirror to be invisible, two calibrations
(read back from frame 2 after composing a card — `--ez ramp true [--ez full256 true]`):

- **Rotation (Nomad, portrait UI on a landscape panel):** `panelX = screenY`,
  `panelY = 1403 − screenX`.
- **Compositor grey → 4-bit level** (Android grey value ranges): 0–75 → 0 · 76–87 → 1 ·
  88–99 → 2 · 100–107 → 3 · 108–119 → 4 · 120–131 → 5 · 132–139 → 6 · 140–151 → 7 ·
  152–167 → 8 · **168–187 → 10 (level 9 is never produced)** · 188–195 → 11 · 196–203 → 12 ·
  204–215 → 13 · 216–223 → 14 · 224–255 → 15. Draw the mirror with one grey from the matching
  range and the recompose is a no-op on the panel.

Other doors seen but not walked: `MISCCTL` (`CLERA_PW_RECT`, `SYNCWIN`), the pen-write path
(frame 1, mode 9, flag 1|5 — only black showed, consistent with a 1-bit overlay), and the
boot-classpath `EinkPWCoreController` (`nativeAddPWRect` → `set_pw_fsb` / `postEinkPWRectFastHL`
in `libeinkpwcorejni.so`, which also drives the RGA blitter).

Intent extras for adb: `--ez auto true` (open, GETINFO, GETCFG, map) · `--ei mode/frame/flag/v0/v1`
(paint the band rect) · `--ei paintDelay ms` · `--ez quiet true` · `--ez noDisp true` ·
`--ez dump true --ei dumpAt ms` (frames → external cache dir) · `--ez cadence true` ·
`--ez fullscreen true` · `--ez ramp true [--ez full256 true]`.
