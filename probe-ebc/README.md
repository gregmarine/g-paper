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

Not yet wired: `mmap` of the frame buffers, `DISPAREA`, `MISCCTL` — their struct layouts
come next, from `postEinkHostBmpRect*` / `postEinkPWRectFast*` in the same library.
