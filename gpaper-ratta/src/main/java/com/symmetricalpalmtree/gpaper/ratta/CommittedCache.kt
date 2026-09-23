package com.symmetricalpalmtree.gpaper.ratta

/**
 * Whether the engine's image of the committed page is **current** when a redraw arrives —
 * the little state the direct stroke path keeps so that a redraw can tell an already-laid
 * change from one it has to re-render whole (Phase 42). Pure, so the one ordering that
 * would show as a stale object on the glass is settled on the JVM.
 *
 * The base funnels every content mutation through one `redrawCommitted()` and does not
 * say why. Most callers have a rect and lay it themselves before they redraw — a mark's
 * runs at pen-up, an erase batch under the tip, an undone stroke's bounds — and mark the
 * image current; the redraw then only records the window's mirror. A caller with no rect
 * (a load, a template, host content) leaves the image stale and the redraw rebuilds the
 * whole page. **A stale mark beats a current one whenever both are made before one
 * redraw**: a host's `notifyContentChanged` landing between an erase batch and the sweep's
 * end must still cost a whole rebuild, or the object it removed stays in the picture.
 */
internal class CommittedCache {

    private var current = false
    private var stale = false

    /** A change was laid into the image already; the next redraw may mirror it. */
    fun markCurrent() {
        current = true
    }

    /** Something changed that nothing laid; the next redraw must rebuild, whatever else
     *  was marked. */
    fun invalidate() {
        stale = true
    }

    /** The redraw is here: true if the image is current and a mirror will do, false if it
     *  must be rebuilt whole. Either way the state resets for the next round. */
    fun take(): Boolean {
        val mirror = current && !stale
        current = false
        stale = false
        return mirror
    }
}
