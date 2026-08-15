package com.symmetricalpalmtree.gpaper.core

/**
 * The active stylus tool. Set via [PaperView.tool]; finger input is never a tool
 * (g-paper is stylus-only — finger events pass through to the host untouched).
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
     * reported via the selection callbacks on [PaperListener]. Implemented in Phase 5;
     * declared now so the tool surface is stable.
     */
    LASSO,
}
