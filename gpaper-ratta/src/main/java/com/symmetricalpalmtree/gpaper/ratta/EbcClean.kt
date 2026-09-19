package com.symmetricalpalmtree.gpaper.ratta

/**
 * The idle clean pass: the three numbers that decide **when** the panel is wiped of the
 * direct path's halo, and **how** (2026-09-19 maintenance, the first walk of 0.1.41
 * inside NSE · Sketch).
 *
 * **Why there is one at all.** The live path updates only the pixels that changed, with
 * mode 7 over exactly the rect the flecks landed in, and the pen-up mirror finds the very
 * same pixels already right and drives nothing — which is the whole point of the mirror
 * and is also why the halo each partial update leaves around its rect is never cleaned up.
 * The bake it replaced was one compositor post over the whole stroke area, and that post
 * re-drove every pixel in it; a thousand small partial updates do not. The artist's words
 * after the first walk: *more ghosting than before.*
 *
 * **What the clean is.** A full-waveform drive of every pixel in the rect the direct path
 * has been painting — the same picture, driven properly rather than patched. It is
 * **mode 4** with the buffer written on the **0…60 scale** (`level × 4`) rather than the
 * live path's mode 7 and 0…15: that is what the probe measured of this driver
 * (`probe-ebc/README.md` is the door those measurements came through), and by eye it
 * *"paints quickly, seems okay"*. The price is that its ioctl blocks about **40 ms** and
 * drives every pixel, so it must never run while the pen is down — it would fight the live
 * path for the panel and the hand would feel the block.
 *
 * **These three are a starting point, not a finding.** The mode and the scale are what the
 * probe read out of the driver; the idle time is a guess at when a hand has stopped
 * writing. All three may move on the artist's word — which is why they are in one place
 * with this note on them rather than spelled into the two files that use them.
 */
internal object EbcClean {

    /** The display mode an idle clean sends: a full-waveform drive of every pixel in the
     *  rect. See the object KDoc; the live path's mode is [EbcDisplayArg.MODE_GREY16]. */
    const val MODE = 4

    /** What a 4-bit level is multiplied by in the frame buffer for [MODE] — this mode
     *  reads the 0…60 scale where mode 7 reads 0…15. */
    const val SCALE = 4

    /** How long after the last pen-up the clean fires, in ms. Re-armed by every contact
     *  and cancelled by the next pen-down, so it only ever runs on a hand that has
     *  stopped. A starting value. */
    const val IDLE_MS = 1500L
}
