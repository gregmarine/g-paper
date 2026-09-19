package com.symmetricalpalmtree.gpaper.ratta

/**
 * Screen coordinates → panel coordinates (Phase 28). Pure Kotlin, no Android imports, so
 * the one thing that would be invisible on-device until a mark landed in the wrong quarter
 * of the panel is settled by a JVM test instead.
 *
 * **The panel is not always the screen.** The Nomad's panel is a landscape 1872 × 1404 buffer
 * under a portrait 1404 × 1872 UI, and the compositor rotates on its way there; the Manta's
 * panel *is* the screen, byte for byte. Both were read back out of the driver's own frames
 * with the probe app (`probe-ebc/README.md`), and the discriminator is the geometry itself
 * — the panel size versus the screen size — not the model, because the Manta reports itself
 * as a Nomad in every build property and would defeat any device check.
 *
 * The rotation is the quarter turn the measurement found: `panelX = screenY`,
 * `panelY = panelH − 1 − screenX`.
 */
internal object EbcGeometry {

    /**
     * Whether the panel buffer is a quarter turn away from the screen, judged by geometry:
     * a panel whose width is the screen's width is the screen (the Manta), and one whose
     * width is the screen's *height* is turned (the Nomad).
     */
    fun isRotated(panelW: Int, panelH: Int, screenW: Int, screenH: Int): Boolean =
        panelW != screenW || panelH != screenH

    /** Byte offset of screen pixel ([screenX], [screenY]) in a frame of [panelW] × [panelH]. */
    fun panelIndex(
        rotated: Boolean,
        panelW: Int,
        panelH: Int,
        screenX: Int,
        screenY: Int,
    ): Int = if (rotated) {
        (panelH - 1 - screenX) * panelW + screenY
    } else {
        screenY * panelW + screenX
    }

    /**
     * The screen rect `[left, top, right, bottom)` as the panel rect DISPAREA wants, written
     * into [into] as `l, t, r, b`.
     *
     * Turned, the screen's y becomes the panel's x and the screen's x becomes the panel's y
     * counted from the far edge — so the right edge of the screen rect gives the panel rect's
     * *top*, and the left edge its bottom. Getting that pair the wrong way round yields a
     * rect with a negative height, which the driver answers by refreshing nothing at all.
     */
    fun panelRect(
        rotated: Boolean,
        panelH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        into: IntArray,
    ) {
        if (rotated) {
            into[0] = top
            into[1] = panelH - right
            into[2] = bottom
            into[3] = panelH - left
        } else {
            into[0] = left
            into[1] = top
            into[2] = right
            into[3] = bottom
        }
    }
}
