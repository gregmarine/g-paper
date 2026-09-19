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

- **Rotation.** Nomad (portrait UI on a landscape 1872×1404 panel): `panelX = screenY`,
  `panelY = 1403 − screenX`. **Manta (A5 X2, 1920×2560 panel = the screen): identity.** Chosen
  by geometry (`panelW != screenW`). On the Manta frame 0 (and 3) hold the screen; frames 2 and
  4 hold a horizontally doubled copy (another role — read the LUT from frame 0 there), and the
  daemon overlay frame 1 clears to `0x90` rather than `0xff`. Atelier's `_a5x2` path is the same
  byte-per-pixel copy at stride 1920 — no packing anywhere.
- **Compositor grey → 4-bit level** (Android grey value ranges): 0–75 → 0 · 76–87 → 1 ·
  88–99 → 2 · 100–107 → 3 · 108–119 → 4 · 120–131 → 5 · 132–139 → 6 · 140–151 → 7 ·
  152–167 → 8 · **168–187 → 10 (level 9 is never produced)** · 188–195 → 11 · 196–203 → 12 ·
  204–215 → 13 · 216–223 → 14 · 224–255 → 15. **Identical on the Manta** — the table is the
  firmware's, not the panel's. Draw the mirror with one grey from the matching range and the
  recompose is a no-op on the panel.

## The live stroke (`DrawActivity`, 2026-09-18, the user's hand)

`am start -n com.symmetricalpalmtree.gpaper.probe/.DrawActivity [--ez flecks true] [--ez mirror false] [--ei flag N]`
— a stylus lays dabs; every MotionEvent writes them into frame 0 (rotated) and sends one
DISPAREA mode 7 for the dirty rect; the window is mirrored at pen-up (`Mirror: UP`, Atelier's
way), live, or never. Finger buttons along the top (no Back key on a Supernote): Dab · Flag ·
Mirror · Clear · Exit.

Measured: event → ioctl return averages 0.4–0.9 ms; the ioctl blocks up to ~65 ms while the
previous update is in flight. Mirror modes and flags 0 / 1 / 4 / 5 all look the same to the
eye. Atelier's own call (`repaint::display_rect_after_set` in libspaint) is mode 7, frame 0,
flag 1 — the same call, so the flag is not a waveform switch.

**What the hand found — the physics:** a solid grey dab shaded by pressure lands **black
first and then lightens** toward its target, trailing the nib, while a black dab lands at once
and stays. That is the 16-grey partial waveform: it passes through black on the way to a grey.
**Black-only flecks whose density follows pressure "work perfectly — very much like Atelier."**
Atelier's pencils are a MyPaint dab scatter; the shade is chosen, not pressure-driven.

## Atelier read back (2026-09-19): shades are densities of black

`adb shell am broadcast -a com.symmetricalpalmtree.gpaper.probe.DUMP` dumps the frames while
another app is in front. With Atelier's HB pencil and one stroke per shade of its sixteen-step
Grayscale palette on screen, frame 0 held **level 0 only** for every stroke — Atelier never sends
the panel a grey for a pencil; a lighter shade is a sparser scatter of black. Black pixels per
pixel of stroke length, panel frame, Nomad, one stroke each (shade hex from Atelier's palette):

| shade | 000000 | 505050 | 606060 | 686868 | 707070 | 808080 | 888888 | 909090 | a0a0a0 | aaaaaa | b6b6b6 | c0c0c0 | c8c8c8 | d0d0d0 | dddddd |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| px/px | 2.67 | 2.19 | 2.05 | 1.93 | 1.85 | 1.67 | 1.55 | 1.49 | 1.28 | 1.12 | 0.99 | 0.85 | 0.72 | 0.67 | 0.40 |
| rel. | 1.00 | 0.82 | 0.77 | 0.72 | 0.69 | 0.63 | 0.58 | 0.56 | 0.48 | 0.42 | 0.37 | 0.32 | 0.27 | 0.25 | 0.15 |

Fit (within a few %): **relative density = 1 − 0.85 · (luma / 221)^1.5**, luma < 224; white is a
lightener, not a density. This is the seed for `RattaPencilInk` in `gpaper-ratta` (Phase 28).

Other doors seen but not walked: `MISCCTL` (`CLERA_PW_RECT`, `SYNCWIN`), the pen-write path
(frame 1, mode 9, flag 1|5 — only black showed, consistent with a 1-bit overlay), and the
boot-classpath `EinkPWCoreController` (`nativeAddPWRect` → `set_pw_fsb` / `postEinkPWRectFastHL`
in `libeinkpwcorejni.so`, which also drives the RGA blitter).

Intent extras for adb: `--ez auto true` (open, GETINFO, GETCFG, map) · `--ei mode/frame/flag/v0/v1`
(paint the band rect) · `--ei paintDelay ms` · `--ez quiet true` · `--ez noDisp true` ·
`--ez dump true --ei dumpAt ms` (frames → external cache dir) · `--ez cadence true` ·
`--ez fullscreen true` · `--ez ramp true [--ez full256 true]`.

## Reference images (`reference/`, 2026-09-19)

- `atelier-hb-16-shades-nomad.png` — Atelier, HB pencil, one stroke per shade of its Grayscale
  palette (black · 505050 · 606060 · 686868 · 707070 · 808080 · 888888 · 909090 · a0a0a0 · aaaaaa ·
  b6b6b6 · c0c0c0 · c8c8c8 · d0d0d0 · dddddd · white), the palette open. The tone reference.
- `atelier-pencil-menu-nomad.png` — Atelier's seven pencils: **4H · 2H · HB · 2B · 4B · 6B · 8B**,
  each with its sample stroke (the Hs pale and fine, the Bs darker and softer). The base for a
  g-paper pencil library — a later phase.
- `gpaper-0.1.41-15-shades-nomad.png` / `-manta.png` — the g-paper demo at the Phase 28 freeze,
  7 px lead, shades 0–14 (`#000000 … #EEEEEE`), the display dithered. Core tone equals the swatch
  grey within 2 % at every shade (gamma 1.0).
