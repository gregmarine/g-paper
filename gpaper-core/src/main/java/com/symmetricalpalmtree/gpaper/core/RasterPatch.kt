package com.symmetricalpalmtree.gpaper.core

import android.graphics.Rect

/**
 * A rectangle of a raster page and the pixels that belong in it (0.1.29).
 *
 * [rect] is page space — the same space [PaperListener.onRasterWillChange] speaks — and
 * [pixels] is row-major ARGB, `rect.width()` per row, `rect.width() * rect.height()`
 * long: exactly what `Bitmap.getPixels` writes and `Bitmap.setPixels` reads, so moving
 * pixels between the page and a patch is one copy with no format in between.
 *
 * This is the unit of a raster host's history. [PaperView.readPageRaster] hands one out
 * as the before-image of a change about to happen, and [PaperView.swapPageRaster]
 * takes it back: the pixels go onto the page and the array is left holding what the
 * page held there, so the same patch, unchanged in shape, serves the redo. Nothing here
 * is a bitmap, and that is deliberate: a host keeps hundreds of these for a sitting and
 * bounds them by bytes, and an `IntArray` is an honest number of bytes with no native
 * allocation behind it to account for separately. `bytes` says what one costs.
 *
 * The array is the host's to keep and the engine's only while a call is running; the
 * engine never holds a reference to it afterwards.
 */
class RasterPatch(val rect: Rect, val pixels: IntArray) {

    /** What this patch costs to keep, for a host's byte budget. */
    val bytes: Long get() = pixels.size * 4L

    init {
        require(!rect.isEmpty) { "a raster patch needs a rect with area; got $rect" }
        require(pixels.size == rect.width() * rect.height()) {
            "a raster patch of ${rect.width()}×${rect.height()} needs " +
                "${rect.width() * rect.height()} pixels; got ${pixels.size}"
        }
    }
}
