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
