package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way a rect's bytes land in the dither image ([DitherCost]).
 *
 * The rule is stated here rather than on a device because it is arithmetic about area,
 * and the only thing a walk can answer is where the fraction should sit — not whether a
 * page counts as a page.
 */
class DitherCostTest {

    private companion object {
        // A Nomad page, which is the one every number in Phase 28 was measured on.
        const val PAGE_W = 1404
        const val PAGE_H = 1872
        const val EIGHTH = 0.125f
        const val MAX_RECTS = 8
    }

    private fun prefer(w: Int, h: Int, fraction: Float = EIGHTH) =
        DitherCost.preferWholeCopy(w, h, PAGE_W, PAGE_H, fraction)

    @Test
    fun `the whole page takes the copy`() {
        assertTrue(prefer(PAGE_W, PAGE_H))
    }

    @Test
    fun `a stroke's bounds does not`() {
        // A 300 × 300 mark is about 3 % of the page.
        assertFalse(prefer(300, 300))
    }

    @Test
    fun `an erase corridor does not`() {
        assertFalse(prefer(PAGE_W, 40))
    }

    @Test
    fun `the threshold is the fraction of the page's AREA, from it upward`() {
        val eighth = (PAGE_W.toLong() * PAGE_H / 8).toInt()
        val justUnder = eighth / PAGE_W - 1
        val atLeast = eighth / PAGE_W + 1
        assertFalse(prefer(PAGE_W, justUnder))
        assertTrue(prefer(PAGE_W, atLeast))
    }

    @Test
    fun `a tall narrow rect and a wide short one of the same area answer the same`() {
        // Half the page, twice over — the rule is about area and nothing else.
        assertTrue(prefer(PAGE_W / 2, PAGE_H))
        assertTrue(prefer(PAGE_W, PAGE_H / 2))
    }

    @Test
    fun `a bigger fraction holds more rects back`() {
        val quarterPage = PAGE_H / 4
        assertTrue(prefer(PAGE_W, quarterPage, fraction = EIGHTH))
        assertFalse(prefer(PAGE_W, quarterPage, fraction = 0.5f))
    }

    @Test
    fun `an empty rect or an unsized page lands nothing either way`() {
        assertFalse(prefer(0, PAGE_H))
        assertFalse(prefer(PAGE_W, 0))
        assertFalse(DitherCost.preferWholeCopy(10, 10, 0, 0, EIGHTH))
    }

    @Test
    fun `a rect bigger than the page is still the page`() {
        // regenDither clips before it asks, but a rule that needed the clip to be right
        // would be a rule with a trap in it.
        assertTrue(prefer(PAGE_W * 2, PAGE_H))
    }

    // ── A mark's runs, landed together ───────────────────────────────────────

    private fun preferRuns(
        runs: Int,
        unionW: Int,
        unionH: Int,
        fraction: Float = EIGHTH,
        maxRects: Int = MAX_RECTS,
    ) = DitherCost.preferWholeCopyForRuns(
        runs, unionW, unionH, PAGE_W, PAGE_H, fraction, maxRects,
    )

    @Test
    fun `a few small runs land one at a time`() {
        // A short mark: a handful of runs down a corridor, nowhere near the page.
        assertFalse(preferRuns(3, 300, 300))
    }

    @Test
    fun `more runs than the cap take the page copy however small they are`() {
        // The scribble: sixty-four runs, each one of them tiny. This is the case the
        // rule exists for — no single rect is slow, and all of them together were 651 ms.
        assertTrue(preferRuns(64, 40, 40))
        assertTrue(preferRuns(MAX_RECTS + 1, 1, 1))
    }

    @Test
    fun `the cap is a ceiling, not a threshold`() {
        assertFalse(preferRuns(MAX_RECTS, 100, 100))
        assertTrue(preferRuns(MAX_RECTS + 1, 100, 100))
    }

    @Test
    fun `a large union takes the copy even from one run`() {
        // The area rule still applies: a corner-to-corner mark whose runs span half the
        // page is the single-rect case wearing a different hat.
        assertTrue(preferRuns(1, PAGE_W, PAGE_H / 2))
        assertTrue(preferRuns(2, PAGE_W, PAGE_H / 4))
    }

    @Test
    fun `one run answers exactly as the single-rect rule does`() {
        // The batch form must never disagree with the form the erase and undo paths use.
        for (h in intArrayOf(1, 40, PAGE_H / 16, PAGE_H / 8, PAGE_H / 2, PAGE_H)) {
            assertEquals(
                "h=$h", prefer(PAGE_W, h), preferRuns(1, PAGE_W, h),
            )
        }
    }

    @Test
    fun `no runs and an unsized page land nothing either way`() {
        assertFalse(preferRuns(0, PAGE_W, PAGE_H))
        assertFalse(preferRuns(-1, PAGE_W, PAGE_H))
        // Even past the cap: an unsized page has no bitmap to copy.
        assertFalse(
            DitherCost.preferWholeCopyForRuns(64, 10, 10, 0, 0, EIGHTH, MAX_RECTS),
        )
    }

    @Test
    fun `a bigger cap holds more batches back`() {
        assertTrue(preferRuns(16, 100, 100, maxRects = 8))
        assertFalse(preferRuns(16, 100, 100, maxRects = 32))
    }
}
