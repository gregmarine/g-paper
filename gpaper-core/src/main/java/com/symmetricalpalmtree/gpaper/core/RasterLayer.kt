package com.symmetricalpalmtree.gpaper.core

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle

/**
 * Which of a raster page's images a mark belongs to (0.1.39; three of them since 0.1.68).
 *
 * The page used to be one ARGB bitmap, and **a pixel does not know which tool laid it**.
 * That was fine until a gel pen joined the pencil on the same page: the rubber lifted
 * alpha wherever it swept, so ink came up exactly as graphite did, and no colour key
 * could tell them apart honestly — a black pencil and a black pen are the same pixel.
 * The artist's rule is the physical one (2026-09-17): *"in the real world, ink is more
 * permanent than pencil."* So the answer is the page's data model rather than a test on
 * what is already there — **two rasters, one picture.** Three since 0.1.68 (Phase 51): the
 * **marker** has an image of its own, because a marker is translucent and everything under
 * it has to stay visible through it, which no pixel shared with the ink could promise.
 *
 * The routing is by style and lives in [of], once: [StrokeStyle.PENCIL] lands in
 * [GRAPHITE], [StrokeStyle.MARKER] in [MARKER], every other style in [INK]. The rubber
 * ([com.symmetricalpalmtree.gpaper.core.PaperView.eraserRadius] on a raster page) and the
 * smudge read and write [GRAPHITE] only — the ink and marker rasters are never read, never
 * allocated and never announced by an erase or a rub. The marker comes off by undo alone
 * (the host's decision, 2026-10-08), as ink does.
 *
 * **These are not user-facing layers.** There is no z-order to choose and no visibility to
 * toggle. The page is seen as the ink image drawn **over** the graphite image (`SRC_OVER`,
 * 0.1.44 — the user's decision that a white gel pen writes over anything; 0.1.39–0.1.43
 * met the two through `DARKEN`, which could never show a pale ink over darker graphite),
 * and the marker image over both. The order is the media's, not a choice: gel ink sits on
 * the sheet over graphite, and graphite laid over dry ink mostly slides off, so pencil over
 * an ink line is hidden by it; a marker is laid last and shows what is under it. Each
 * marker stroke is baked at its own translucency (`StrokeRenderer`'s 45 %), `SRC_OVER`
 * into the marker image, so two strokes that cross are darker where they cross, as two
 * passes of a real marker are. On white paper with black ink the two operators are
 * pixel-identical. The enum's order is the flatten's order.
 *
 * **An un-layered raster call means [GRAPHITE]** — every `loadPageRaster(bitmap)`,
 * `getPageRaster()`, `copyPageRaster(rect)`, `readPageRaster(rect)`,
 * `swapPageRaster(patches)` and every un-layered
 * [PaperListener.onRasterWillChange] / [PaperListener.onRasterChanged]. That is not a
 * default chosen for tidiness: every host written before 0.1.39 drew a pencil and nothing
 * else, so graphite is the page it already had, and it goes on compiling and behaving
 * exactly as it did.
 */
enum class RasterLayer {

    /** The pencil's page. What the rubber rubs, and the only layer an erase touches. */
    GRAPHITE,

    /** Everything that is not a pencil or a marker. Permanent under the rubber, by the
     *  artist's rule. */
    INK,

    /** The marker's page (0.1.68): translucent strokes laid over graphite and ink alike,
     *  never rubbed, never smudged, off by undo alone. */
    MARKER;

    companion object {

        /**
         * Which raster [style] bakes into — the one place the routing is decided, so a
         * style added to [StrokeStyle] lands somewhere by construction rather than by
         * being remembered. The pencil is graphite, the marker is the marker, everything
         * else is ink.
         */
        fun of(style: StrokeStyle): RasterLayer = when (style) {
            StrokeStyle.PENCIL -> GRAPHITE
            StrokeStyle.MARKER -> MARKER
            else -> INK
        }
    }
}
