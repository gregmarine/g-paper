package com.symmetricalpalmtree.gpaper.core.canvas

/**
 * The lasso gestures' live trail, as chrome: what an engine that paints the trail itself
 * draws under the pen (the base view in its window; the Supernote direct path onto the
 * panel, Phase 42/43). Two appearances, one per lasso, so the two tools can be told apart
 * while the loop is still open — the firmware daemon's own distinction on Supernote:
 *
 * - [Tool.LASSO][com.symmetricalpalmtree.gpaper.core.Tool.LASSO]: a dashed line,
 *   [WIDTH_PX] wide, [DASH_ON_PX] on / [DASH_OFF_PX] off — the selection chrome.
 * - [Tool.LASSO_ERASER][com.symmetricalpalmtree.gpaper.core.Tool.LASSO_ERASER]: a stream of
 *   small x-marks along the path, one every [CROSS_PITCH_PX] of arc length (10 px — the arms nearly touch; 16 px read too sparse on the user's Nomad), each arm
 *   [CROSS_ARM_PX] long, drawn [WIDTH_PX] wide — the Supernote lasso-eraser look, the
 *   committed [StrokeStyle.CROSS][com.symmetricalpalmtree.gpaper.core.model.StrokeStyle.CROSS]
 *   at chrome size.
 *
 * Whole pixels, aliased, black: a crisp mark reads better than a soft one on an EPD panel.
 */
object LassoTrailChrome {
    const val WIDTH_PX = 2f
    const val DASH_ON_PX = 12f
    const val DASH_OFF_PX = 8f
    const val CROSS_PITCH_PX = 10f
    const val CROSS_ARM_PX = 5f
}
