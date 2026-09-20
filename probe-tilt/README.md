# probe-tilt — what the Supernote stylus reports per sample

A from-scratch, dependency-free app (nothing from g-paper) that writes one CSV row per
stylus sample — tilt, orientation, pressure, size, distance, tool type, hover — so a
threshold angle for *"the lead is on its side"* can be fitted offline. The ink it draws
is feedback for the hand (it widens past 50° of tilt); the CSV is the product.

    ./gradlew :probe-tilt:assembleDebug
    adb -s SN078D10012852 install -r probe-tilt/build/outputs/apk/debug/probe-tilt-debug.apk
    adb -s SN078D10012852 shell am start -n com.symmetricalpalmtree.gpaper.probetilt/.MainActivity
    adb -s SN078D10012852 pull /sdcard/Android/data/com.symmetricalpalmtree.gpaper.probetilt/files/ .

`adb -s SN078D10012852 logcat -s TiltProbe:I` shows every tenth row while the hand works.

## The five passes

The top bar names the pass; write it, tap **Next pass** (which clears the ink), write the
next. In order: **upright** — the pen held as vertically as the hand can hold it;
**writing** — ordinary handwriting at the grip the hand actually uses; **shading** — the
pen laid over deliberately, shading broad strokes on its side; **flat** — the pen laid as
flat as the digitizer still tracks; **free** — anything, rolling between the grips, to
catch the transitions the four fixed passes miss.

## Results — the user's hand, Nomad and Manta, 2026-09-19

**The HAL does not follow Android's axis contract.** `AXIS_TILT` carries signed **tilt-X in
degrees** (integers) and `AXIS_ORIENTATION` signed **tilt-Y in degrees**. Both are live.
There is no polar angle and no orientation as the platform documents them: the lean from
vertical is `hypot(tiltX, tiltY)` **in degrees** — the same quantity, on the same scale, that
the NoteAir5C measurement found on BOOX (`PLAN.md` Phase 11) — and the lean's **direction** is
`atan2(tiltY, tiltX)`, in the **panel's** frame rather than the screen's.

Read as documented, `AXIS_TILT` is radians, so a raw `30` means 1719° of lean. That is the
bug behind `g-paper` Phase 22: on the Nomad the sign happens to be negative and clamps to
upright, which looked perfect; on the Manta it is positive and saturates, which is the
10–15× baked pencil of 2026-09-17. A units bug wearing a design decision's clothes.

Per grip, median polar lean from vertical (p95 / min where it matters):

| grip | Nomad | Manta |
|---|---|---|
| upright | 10° (p95 17°) | 7° (p95 10°) |
| writing | 39° (p95 43°) | 29° (p95 40°) |
| shading | 56° (min 54°) | 61° (min 55°) |
| flat | 54° (min 50°) | 61° (min 56°) |
| physical ceiling | 62° | 72° |

**The two bands that matter touch**: an ordinary writing grip reaches 43° at its p95 and a
deliberate shading grip starts at 54°. So the flank's threshold is the gap itself — nothing
up to 45°, fully out at 54° — and not a fitted midpoint. Both devices also stop tracking well
short of horizontal, so "flat" and "shading" are the same reading in practice.

**Direction.** On the Manta, whose panel *is* the screen, a right-handed shading lean reads
≈42° — `+x`/`+y` meaning the barrel lies toward the screen's right and bottom, the natural
convention (the vector points from the tip toward where the barrel is). On the Nomad, whose
panel is a quarter turn away from the screen, the same grip reads ≈137° in the raw axes, and
mapping `(x, y) → (y, −x)` brings it to ≈47°. The magnitude needs none of this: a hypotenuse
does not care how the axes are turned.

Both findings are carried in `g-paper` by `EbcGeometry.screenAzimuth` and
`RattaPaperView.sampleTilt` / `sampleAzimuth` (Phase 36, 0.1.51), with the 42°/137° pair
pinned as a test.
