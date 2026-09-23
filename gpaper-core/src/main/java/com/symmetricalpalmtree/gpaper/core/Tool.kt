package com.symmetricalpalmtree.gpaper.core

/**
 * The active stylus tool. Set via [PaperView.tool]; finger input is never a tool
 * (g-paper is stylus-only — finger events pass through to the host untouched), with
 * one narrow exception: while a [LASSO] selection is active, a single finger inside
 * the selection box drags it and a finger tap outside dismisses it (palm-gated via
 * [PaperView.isPenActive]; the dismissal commits after a
 * [PaperView.PEN_ACTIVE_TAIL_MS] escrow). Hosts with their own touch listeners on the
 * paper view must yield finger events while a selection is active.
 */
enum class Tool {
    /**
     * View-only: the surface renders content but captures no stylus input. On EPD
     * engines the hardware ink overlay is disabled. Stylus events still reach the
     * host's [RawInputListener].
     */
    NONE,

    /** Draw strokes with the armed color/width ([PaperView.penColor], [PaperView.penWidth]). */
    PEN,

    /**
     * Stroke eraser: hit-tested against whole strokes within [PaperView.eraserRadius];
     * erased strokes are reported via [PaperListener.onStrokesErased]. Host content is
     * never erased by the component.
     */
    ERASER,

    /**
     * Lasso selection: a freehand outline selects strokes (and host content that exposes
     * hit targets), then drag-move mechanics run inside the component with results
     * reported via the selection callbacks on [PaperListener]. The live trail is
     * engine chrome (hardware dash styles on the EPD engines, a dashed Canvas overlay
     * on generic) and never enters the stroke model.
     */
    LASSO,

    /**
     * Lasso eraser (0.1.28): a freehand outline **erases** instead of selecting. The
     * outline is captured exactly as [LASSO]'s and decided on the lasso's own hit rule —
     * a stroke goes if any of its points lies inside the loop, host content (a
     * [com.symmetricalpalmtree.gpaper.core.render.ContentRenderer.hitTargets] rect) goes
     * whole if the loop touches its box — so what a lasso would have selected is exactly
     * what the lasso eraser takes. Nothing is ever selected: no box, no drag, no
     * selection callbacks, no [PaperListener.onPaperTapped]. The hit strokes leave the
     * model and the whole gesture is reported once through
     * [PaperListener.onLassoErased]; host content is reported, never removed, by the
     * component. A loop that takes nothing reports nothing. The barrel button / eraser
     * end still point-erases, as under [LASSO]. Live trail: engine chrome (the Supernote
     * lasso-eraser x-stream on Ratta, the lasso's trail elsewhere).
     */
    LASSO_ERASER,

    /**
     * Stylus smudge (0.1.60): the nib smudges the **graphite** raster exactly as the
     * host-driven finger smudge does ([PaperView.beginSmudge] / [PaperView.smudgeAlong] /
     * [PaperView.endSmudge]), but the engine's own touch handling drives it — a stylus
     * contact is the sweep, within [PaperView.smudgeToolRadius] (a stump, narrower than
     * the fingertip's [PaperView.smudgeRadius]). Nothing is drawn, nothing is erased;
     * the ink image is never read. On EPD engines the hardware ink overlay is disabled
     * under it, as under [NONE]. A no-op in stroke mode. The barrel button / eraser end
     * still point-erases, as under every capturing tool.
     */
    SMUDGE,
}
