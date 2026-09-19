package com.symmetricalpalmtree.gpaper.ratta

/**
 * What a raster-pixels change should do with the dithered display image — the little state
 * machine that makes a page open cost **one** whole-page rebuild instead of two
 * (2026-09-19 maintenance).
 *
 * A two-raster page arrives as two calls: `loadPageRaster(GRAPHITE, …)` then
 * `loadPageRaster(INK, …)`, each announcing the whole page and each followed by the redraw
 * that presents it. Rebuilding on each is half the work thrown away, and — worse to the
 * eye — the frame between them shows a page with graphite and no ink, which is exactly
 * what the artist saw: *the pencil first and the ink a moment later.* So a whole-page
 * rebuild is **deferred** to a posted runnable, the second call folds into the first, and
 * the one redraw that presents it runs after the rebuild rather than before.
 *
 * Per-rect changes stay immediate: a stroke bake, an erase batch, an undo patch each
 * precede a present that is already on its way, and a rect deferred would be a mark that
 * appears a frame late. The one interaction between them is that a rect arriving while a
 * whole page is pending needs no work at all — the pending rebuild covers it.
 *
 * Pure Kotlin, no Android imports (not even `Rect` — the caller keeps the geometry), so
 * the ordering rules are settled by a JVM test rather than by a page turn on a device.
 */
internal class DitherCoalescer {

    /** What the caller should do about a change. */
    enum class Action {
        /** Nothing: a pending whole-page rebuild already covers it. */
        NONE,

        /** Rebuild the named rect now, before the caller's own present. */
        REBUILD_RECT,

        /** Post [takeScheduled]'s runnable — nothing is rebuilt or presented until it runs. */
        SCHEDULE_WHOLE,

        /** The page's images went: drop the image, cancel anything posted. */
        DROP,
    }

    /** Whether a whole-page rebuild is posted and waiting. */
    var scheduled = false
        private set

    /** A rect of the page changed. */
    fun onRect(): Action = if (scheduled) Action.NONE else Action.REBUILD_RECT

    /** The whole page changed and still has pixels — a load, a bake of a whole page. */
    fun onWholePage(): Action {
        if (scheduled) return Action.NONE
        scheduled = true
        return Action.SCHEDULE_WHOLE
    }

    /** The page's images were dropped (a content swap, a clear). */
    fun onPageGone(): Action {
        scheduled = false
        return Action.DROP
    }

    /**
     * Whether a redraw should be held back for the pending rebuild.
     *
     * The host's load calls each present as they always have; while a rebuild is pending
     * those presents would show a page half-built or — after a content swap dropped the
     * image — blank. The posted runnable presents once, after the rebuild, which is the
     * same frame count the page turn had before and one correct picture instead of two.
     */
    val deferRedraw: Boolean get() = scheduled

    /** The posted runnable ran: true if it should rebuild and present (false when the
     *  page went, or the panel closed, between the post and the run). */
    fun takeScheduled(): Boolean {
        val was = scheduled
        scheduled = false
        return was
    }

    /** The panel closed or the view went away — forget everything, so nothing is held back
     *  for a rebuild that will never run. */
    fun reset() {
        scheduled = false
    }
}
