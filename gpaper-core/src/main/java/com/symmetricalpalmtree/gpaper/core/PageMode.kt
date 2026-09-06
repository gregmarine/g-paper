package com.symmetricalpalmtree.gpaper.core

/**
 * What a page *is* once the pen lifts (0.1.25).
 *
 * Both modes share everything up to pen-up — the live ink, the palm gate, the EPD
 * handoffs, the committed renderer that bakes a mark in its approved appearance. They
 * part at the moment the mark is kept:
 *
 * - **[STROKE]** keeps the mark as an object. It can be reported, stored as data, erased
 *   whole, moved, and reloaded fleck for fleck. This is the default and the only mode
 *   g-paper had before 0.1.25; a host that never mentions [PageMode] gets exactly the
 *   engine it always had.
 * - **[RASTER]** keeps only the pixels. The mark is composited into one page-sized
 *   image and the object is let go, so `getStrokes()` stays empty and nothing can be
 *   taken back whole — but the page can now be rubbed at, the way graphite on paper
 *   can, which is what Paintsprout asked for: a pencil sketch that is truer to paper
 *   because it *is* pixels. The host owns the image (`loadPageRaster` /
 *   `getPageRaster`) as it owns stroke rows in the other mode.
 *
 * A mode is set on an empty page and never flipped under ink: setting `pageMode` drops
 * whatever content the view holds, exactly as a page turn does, and the host then loads
 * the content the new mode understands. Nothing converts in the engine. A stroke page
 * can be baked into a raster page by loading its strokes in [RASTER] mode; pixels cannot
 * become strokes again, and no API pretends they can.
 */
enum class PageMode {
    /** Marks are kept as [com.symmetricalpalmtree.gpaper.core.model.Stroke] objects. Default. */
    STROKE,

    /** Marks are composited into a page image and the objects are dropped. */
    RASTER,
}
