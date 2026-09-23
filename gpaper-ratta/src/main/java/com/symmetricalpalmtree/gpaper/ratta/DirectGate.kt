package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.PageMode

/**
 * Which pages the Supernote engine paints itself — the two gates, stated once and pure
 * (Phase 42, 0.1.56), so that "is this page ours or the daemon's" is a JVM fact rather
 * than a reading of a `View`.
 *
 * Both need the firmware present (there is nothing to disable otherwise — without the
 * daemon the base draws its own live ink through the window, and the panel is never
 * opened) and the panel driver open. A **raster** page is then ours unconditionally
 * (Phase 29): its flatten base is the page images, which it always has. A **stroke** page
 * is ours only when the host said so ([com.symmetricalpalmtree.gpaper.core.PaperView.directInk]):
 * its flatten base is the committed picture, which the engine must keep as an image of
 * its own, and the daemon it replaces was a working path — an opt-in, never a default.
 *
 * The two are exclusive by construction: one page has one mode.
 */
internal object DirectGate {

    /** A raster page with the panel ours — [RattaPaperView]'s `directRaster`. */
    fun raster(firmware: Boolean, panelOpen: Boolean, mode: PageMode): Boolean =
        firmware && panelOpen && mode == PageMode.RASTER

    /** A stroke page the host opted in with the panel ours — `directStroke`. */
    fun stroke(firmware: Boolean, panelOpen: Boolean, mode: PageMode, directInk: Boolean): Boolean =
        firmware && panelOpen && mode == PageMode.STROKE && directInk

    /** Either — the daemon is full-screen-disabled and every post goes to the panel. */
    fun any(firmware: Boolean, panelOpen: Boolean, mode: PageMode, directInk: Boolean): Boolean =
        raster(firmware, panelOpen, mode) || stroke(firmware, panelOpen, mode, directInk)
}
