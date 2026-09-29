# probe-seam — a hub that owns every encrypted file, and an app that reads through it

Two throwaway apps (nothing from g-paper in the hub; the app uses the real engine only to draw)
that ask one question on a stock Supernote Nomad: **could a notebook app keep no database of its
own, and read and write every page through another app that owns the encrypted files?**
Walked on the Nomad on 2026-09-28. The answer is yes.

- `probe-seam-hub` — owns SQLCipher databases (a raw key, never a passphrase), serves them over
  `ISeam`. The service is guarded by a `signature` permission and checks the caller's signing
  certificate again on every call. Rows and rasters cross in `SharedMemory`, never inline.
- `probe-seam-app` — binds the hub. `MainActivity` runs a timing suite (logcat `SeamProbe`);
  `PageActivity` shows real notebook pages on the real engine, flipped by swipe or button
  (logcat `SeamFlip`). The `stranger` flavour is the same app signed with another key
  (`stranger.keystore`, generated locally, not committed).

```
./gradlew :probe-seam-hub:assembleDebug :probe-seam-app:assembleOursDebug :probe-seam-app:assembleStrangerRelease
adb install -r probe-seam-hub/build/outputs/apk/debug/probe-seam-hub-debug.apk     # the hub first: it declares the permission
adb install -r probe-seam-app/build/outputs/apk/ours/debug/probe-seam-app-ours-debug.apk
adb shell am start -n com.symmetricalpalmtree.gpaper.probeseam.app/.MainActivity
adb shell am start -n com.symmetricalpalmtree.gpaper.probeseam.app/.PageActivity
```

A plaintext `.soil` export goes into the hub's private storage, and is encrypted on first use:

```
adb shell run-as com.symmetricalpalmtree.gpaper.probeseam.hub mkdir -p files/import
adb exec-in run-as com.symmetricalpalmtree.gpaper.probeseam.hub sh -c 'cat > files/import/X.soil' < X.soil
```

## What was measured (medians, Nomad)

| Synthetic page, 1 KiB strokes | Through the seam | Same read inside the hub |
|---|---|---|
| 200 strokes | 40 ms | 22 ms |
| 1,000 strokes | 112 ms | 105 ms |
| 3,000 strokes | 415 ms | 394 ms |

Save of 1 stroke 16–19 ms, of 50 strokes 47–54 ms, at any page size. An empty call 1.8 ms.
The store opens in 80–96 ms.

| Real notebook pages | Seam read | In hub | Decode | Total |
|---|---|---|---|---|
| 780–1,150 strokes | 103–167 ms | 82–121 ms | 99–176 ms | 206–340 ms |
| 39–670 strokes | 11–86 ms | 6–81 ms | 18–124 ms | 31–212 ms |

The first page after launch, cold, was 537 ms. Nothing is cached or prefetched. `loadStrokes`
returns in 1–2 ms; the time to ink on the glass was judged by hand ("almost identical" to the
notebook), not measured.

| Raster | One piece | 128 KiB chunks |
|---|---|---|
| 1 MiB | 27 ms | 47 ms |
| 3 MiB | 750 ms | 1,498 ms |

The 3 MiB figures are dominated by `substr` reads of a large encrypted value inside the hub;
no in-hub baseline was taken for rasters.

**The stranger** was refused at bind: `SecurityException: Not allowed to bind to service`.

## Trap

sqlcipher-android's `rawQuery(String, Object...)` takes a Kotlin `arrayOf<Any>(…)` as **one**
argument. Pass the values themselves.
