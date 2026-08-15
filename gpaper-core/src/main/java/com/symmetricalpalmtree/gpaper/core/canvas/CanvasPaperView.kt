package com.symmetricalpalmtree.gpaper.core.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.RawAction
import com.symmetricalpalmtree.gpaper.core.RawInputEvent
import com.symmetricalpalmtree.gpaper.core.RawInputListener
import com.symmetricalpalmtree.gpaper.core.RawTool
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.geometry.EraseHitTest
import com.symmetricalpalmtree.gpaper.core.geometry.LassoHitTest
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import java.util.UUID

/**
 * The generic Canvas engine — core's [PaperView] implementation and the shared base the
 * Ratta engine extends in Phase 4 (the sibling-copy trap from Notesprout is killed by
 * design: canvas logic lives once, here).
 *
 * **Hosts never construct this directly** — use [GPaper.create]. The class is public only
 * so device engine modules can subclass it; everything protected/open is engine-internal
 * surface, not host API.
 *
 * Rendering model (ported from Notesprout's `GenericNotebookView`):
 * - **Committed layer** — template + host content + baked strokes, recorded into a
 *   hardware [RenderNode] display list. Re-recorded only when content changes (stroke
 *   commit, erase, load, template/renderer change), never per frame; [onDraw] blits it.
 *   A software-canvas fallback draws the vector content directly.
 * - **Live layer** — the in-progress stroke, drawn in [onDraw] at input rate through the
 *   same [StrokeRenderer] as the bake, so live and committed appearance always agree on
 *   this engine.
 *
 * Input is stylus-only: finger events are never consumed, so host gestures work above and
 * around the paper. The pen-activity gate ([isPenActive]) tracks every captured stylus
 * contact plus a [PaperView.PEN_ACTIVE_TAIL_MS] tail.
 */
open class CanvasPaperView(context: Context) : View(context), PaperView {

    private companion object {
        /** Redraw at most this often while the eraser sweeps (erase-path performance rule). */
        const val ERASE_REDRAW_INTERVAL_MS = 60L

        /** Default eraser hit radius in px, mirrored from the reference engines. */
        const val DEFAULT_ERASER_RADIUS_PX = 15f

        /** Redraw at most this often while a lasso trail or drag-move sweeps. */
        const val LASSO_REFRESH_INTERVAL_MS = 60L

        /**
         * Tap-vs-gesture classifier, in dp (reference value): a lasso gesture whose whole
         * extent stays under this is a tap; a drag-move starts once the pen travels this
         * far. Extent, not net displacement — a small circular lasso returns near its
         * origin but spans a real bounding box.
         */
        const val DRAG_THRESHOLD_DP = 8f

        /** The selection box is drawn (and drag-hit) this far outside the tight
         *  [Selection.bounds], so thin selections still present a grabbable box. */
        const val SELECTION_BOX_INFLATE_PX = 12f
    }

    /** What the current stylus contact is doing; latched at ACTION_DOWN. */
    private enum class GestureMode { NONE, DRAW, ERASE, LASSO, DRAG, OBSERVE }

    // ── Stroke model ─────────────────────────────────────────────────────────

    private val strokeList = ArrayList<Stroke>()

    /** Immutable copy for [getStrokes]' any-thread contract; replaced on every mutation. */
    @Volatile
    private var strokeSnapshot: List<Stroke> = emptyList()

    // ── Rendering state ──────────────────────────────────────────────────────

    private val committedNode = RenderNode("gpaper-committed")
    private val scratchPaint = Paint()
    private var templateBitmap: Bitmap? = null
    private var pageWidth = 0
    private var pageHeight = 0
    private val contentRenderers = ArrayList<ContentRenderer>()

    // ── Input state ──────────────────────────────────────────────────────────

    private val activePoints = ArrayList<StrokePoint>()
    private var gestureMode = GestureMode.NONE
    private var lastEraserPoint: StrokePoint? = null
    private var lastEraseRedrawMs = 0L

    /** Host chrome zones where the stylus must not ink — subclasses read them to feed
     *  hardware exclusion (e.g. the Onyx `setLimitRect`). */
    protected var exclusionRects: List<Rect> = emptyList()
        private set

    /**
     * Whether this engine draws the in-progress stroke itself (live layer in [onDraw],
     * invalidated at input rate). Engines whose live ink is painted by firmware
     * (the Ratta EPDC overlay) override to false: the per-move invalidate → full-view
     * redraw → panel update would fight the hardware for no visual gain.
     */
    protected open val rendersLiveStrokes: Boolean get() = true

    /**
     * Whether this engine draws the live lasso trail itself (dashed overlay in [onDraw]).
     * Engines whose trail is painted by hardware override to false (the Ratta firmware's
     * dash pen); the Onyx engine keeps true because its MotionEvent lasso path only runs
     * as the no-pipeline fallback, where no hardware trail exists.
     */
    protected open val rendersLiveTrail: Boolean get() = true

    // ── Selection / lasso state ──────────────────────────────────────────────

    private var selection: Selection? = null

    /** Outline capture for THIS class's MotionEvent path. Device engines whose pipeline
     *  owns the pen (Onyx raw callbacks) buffer their own points and drive the shared
     *  entries ([lassoTryBeginDrag] / [lassoOutlineStart] / [completeLassoOutline]). */
    private val lassoPoints = ArrayList<StrokePoint>()
    private var lassoCapturing = false
    private var lastLassoInvalidateMs = 0L

    // Drag-move state — live from [lassoTryBeginDrag] until finish/cancel.
    private var dragActive = false
    private var dragThresholdMet = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragDx = 0f
    private var dragDy = 0f

    /** Immutable snapshots of the selected strokes, drawn translated during the drag. */
    private var dragStrokes: List<Stroke> = emptyList()

    /** Selected host-content hit targets paired with their renderers. During the drag
     *  each is drawn translated via [ContentRenderer.drawObject] when the renderer
     *  implements it, else as a dashed ghost of its bounds (the host repositions its
     *  objects on [PaperListener.onSelectionMoved] and calls [notifyContentChanged]). */
    private var dragContentTargets: List<Pair<ContentRenderer, HitTarget>> = emptyList()

    /** Stroke ids omitted from the committed record while their translated ghosts are
     *  drawn by the drag layer; empty outside a threshold-crossed drag. */
    private var dragHiddenIds: Set<String> = emptySet()

    /** Content ids passed to the renderers' exclusion-aware draw during the drag
     *  re-record, so opted-in hosts hide the originals; empty outside a drag. */
    private var dragHiddenContentIds: Set<String> = emptySet()

    /** Dashed chrome for the lasso trail, the selection box, and drag ghosts. */
    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = false
    }

    // Pen-gate state. Volatile: device pipelines may report proximity from their raw
    // input thread (the Onyx SDK event bus), while hosts read [isPenActive] on main.
    @Volatile private var penDown = false

    /** Whether the stylus is currently on the glass — for device subclasses whose
     *  deferred hardware handoffs must never fire mid-stroke (the Ratta clear ladder). */
    protected val isPenDown: Boolean get() = penDown
    @Volatile private var penLastLiftMs = 0L
    @Volatile private var penHovering = false
    @Volatile private var penLastHoverMs = 0L
    private var released = false

    // ── Listeners ────────────────────────────────────────────────────────────

    private var paperListener: PaperListener? = null
    private var rawInputListener: RawInputListener? = null

    // ── PaperView: identity, tool & pen configuration ────────────────────────

    override fun asView(): View = this

    override val engineId: String get() = GPaper.ENGINE_GENERIC

    override var tool: Tool = Tool.PEN
        set(value) {
            if (field == value) return
            val leavingLasso = field == Tool.LASSO
            field = value
            cancelActiveGesture()
            if (leavingLasso) clearSelection()
        }

    override var penColor: Int = Stroke.BLACK

    override var penWidth: Float = Stroke.DEFAULT_WIDTH

    override var penStyle: StrokeStyle = StrokeStyle.PEN

    override var eraserRadius: Float = DEFAULT_ERASER_RADIUS_PX

    // ── PaperView: stroke data in ────────────────────────────────────────────

    // Every external model mutation dismisses an active selection first: the selected
    // ids may be about to disappear, and a stale box over changed content is worse than
    // asking the host to re-select (paste flows call setSelection right after anyway).

    override fun loadStrokes(strokes: List<Stroke>) {
        clearSelection()
        strokeList.clear()
        strokeList.addAll(strokes)
        modelChanged()
        redrawCommitted()
    }

    override fun addStrokes(strokes: List<Stroke>) {
        clearSelection()
        strokeList.addAll(strokes)
        modelChanged()
        redrawCommitted()
    }

    override fun removeStrokes(ids: Collection<String>) {
        clearSelection()
        val idSet = ids as? Set<String> ?: ids.toHashSet()
        if (strokeList.removeAll { it.id in idSet }) {
            modelChanged()
            redrawCommitted()
        }
    }

    override fun getStrokes(): List<Stroke> = strokeSnapshot

    override fun clear() {
        clearSelection()
        activePoints.clear()
        strokeList.clear()
        modelChanged()
        redrawCommitted()
    }

    override fun clearForContentSwap() {
        // Model drops now; pixels stay — no re-record, no invalidate. The next
        // loadStrokes() (or other content call) swaps the screen in one repaint.
        clearSelection()
        activePoints.clear()
        strokeList.clear()
        modelChanged()
    }

    // ── PaperView: template & page geometry ──────────────────────────────────

    override fun setTemplate(bitmap: Bitmap?) {
        templateBitmap = bitmap
        redrawCommitted()
    }

    override fun setPageSize(width: Int, height: Int) {
        pageWidth = width
        pageHeight = height
        redrawCommitted()
    }

    // ── PaperView: host content ──────────────────────────────────────────────

    override fun addContentRenderer(renderer: ContentRenderer) {
        contentRenderers.add(renderer)
        redrawCommitted()
    }

    override fun removeContentRenderer(renderer: ContentRenderer) {
        if (contentRenderers.remove(renderer)) redrawCommitted()
    }

    override fun notifyContentChanged() {
        redrawCommitted()
    }

    // ── PaperView: chrome & gesture cooperation ──────────────────────────────

    override fun setExclusionRects(rects: List<Rect>) {
        exclusionRects = rects.map { Rect(it) }
    }

    override fun releaseRender() {
        // Non-EPD engine: the normal View pipeline repaints freely; nothing to release.
    }

    override val isPenActive: Boolean
        get() {
            if (penDown || penHovering) return true
            val now = SystemClock.uptimeMillis()
            // The lift tail covers palm "double taps" after writing; the hover tail
            // bridges HOVER_EXIT → ACTION_DOWN (the pen leaves hover just before it
            // touches) and short dips out of hover range mid-manipulation.
            return (now - penLastLiftMs) < PaperView.PEN_ACTIVE_TAIL_MS ||
                (now - penLastHoverMs) < PaperView.PEN_ACTIVE_TAIL_MS
        }

    // ── PaperView: selection ─────────────────────────────────────────────────

    override fun clearSelection() {
        // A dismissal mid-drag cancels the drag: restore the hidden strokes first.
        val hadDragVisual = dragActive && dragThresholdMet
        dragActive = false
        dragThresholdMet = false
        dragStrokes = emptyList()
        dragContentTargets = emptyList()
        val hadHidden = dragHiddenIds.isNotEmpty() || dragHiddenContentIds.isNotEmpty()
        dragHiddenIds = emptySet()
        dragHiddenContentIds = emptySet()
        if (hadDragVisual) onSelectionDragVisual(false)
        val had = selection != null
        selection = null
        if (hadHidden) redrawCommitted() else if (had) invalidate()
        if (had) paperListener?.onSelectionDismissed()
    }

    override fun setSelection(strokeIds: Set<String>, contentIds: Set<String>, bounds: Bounds) {
        // Host-initiated (paste flow): no onSelectionCreated echo — the host already knows.
        if (dragActive) clearSelection()
        selection = Selection(strokeIds, contentIds, bounds)
        invalidate()
    }

    // ── PaperView: lifecycle ─────────────────────────────────────────────────

    override fun resumeDrawing() {
        // Non-EPD engine: input capture is always live; nothing to reclaim. Idempotent.
    }

    override fun releaseForHandoff() {
        // Non-EPD engine: no process-global pen pipeline to hand off.
    }

    override fun release() {
        released = true
        // Drop selection state without callbacks — the host is tearing the view down.
        selection = null
        dragActive = false
        dragThresholdMet = false
        dragStrokes = emptyList()
        dragContentTargets = emptyList()
        dragHiddenIds = emptySet()
        dragHiddenContentIds = emptySet()
        lassoCapturing = false
        lassoPoints.clear()
        activePoints.clear()
        strokeList.clear()
        modelChanged()
        contentRenderers.clear()
        templateBitmap = null
        committedNode.discardDisplayList()
    }

    // ── PaperView: output & listeners ────────────────────────────────────────

    override fun renderToBitmap(): Bitmap? {
        val w = width
        val h = height
        if (w == 0 || h == 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawCommittedContent(Canvas(bitmap))
        return bitmap
    }

    override fun setPaperListener(listener: PaperListener?) {
        paperListener = listener
    }

    override fun setRawInputListener(listener: RawInputListener?) {
        rawInputListener = listener
    }

    // ── Input capture ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (released) return false
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER
        // Stylus-only, with one narrow exception: while a selection is active in lasso
        // mode, a single finger may drag it or dismiss it (see handleFingerSelection).
        // Every other finger (and mouse) event passes through to the host untouched.
        if (!isStylus) return handleFingerSelection(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Host chrome zones never start ink; let the platform route the event.
                if (exclusionRects.any { it.contains(event.x.toInt(), event.y.toInt()) }) {
                    return false
                }
                markPenDown()
                gestureMode = when {
                    tool == Tool.NONE -> GestureMode.OBSERVE
                    // Barrel button / stylus eraser end erases in every capturing tool —
                    // lasso included (an erase contact must never become an outline).
                    toolType == MotionEvent.TOOL_TYPE_ERASER ||
                        tool == Tool.ERASER ||
                        (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    -> GestureMode.ERASE
                    tool == Tool.LASSO ->
                        if (lassoTryBeginDrag(event.x, event.y)) {
                            GestureMode.DRAG
                        } else {
                            lassoOutlineStart()
                            lassoCapturing = true
                            lassoPoints.clear()
                            lassoPoints.add(event.strokePointAt(-1))
                            GestureMode.LASSO
                        }
                    else -> GestureMode.DRAW
                }
                dispatchRaw(event, toolType)
                when (gestureMode) {
                    GestureMode.DRAW -> {
                        activePoints.clear()
                        appendDrawPoints(listOf(event.strokePointAt(-1)))
                        if (rendersLiveStrokes) invalidate()
                    }
                    GestureMode.ERASE -> {
                        lastEraserPoint = null
                        eraseAlong(listOf(event.strokePointAt(-1)))
                    }
                    else -> Unit
                }
            }

            MotionEvent.ACTION_MOVE -> {
                dispatchRaw(event, toolType)
                val newPoints = event.batchStrokePoints()
                when (gestureMode) {
                    GestureMode.DRAW -> {
                        appendDrawPoints(newPoints)
                        if (rendersLiveStrokes) invalidate()
                    }
                    GestureMode.ERASE -> eraseAlong(newPoints)
                    GestureMode.LASSO -> {
                        lassoPoints.addAll(newPoints)
                        if (rendersLiveTrail) throttledLassoInvalidate()
                    }
                    GestureMode.DRAG -> lassoDragMove(event.x, event.y)
                    else -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                markPenUp()
                dispatchRaw(event, toolType)
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when (gestureMode) {
                    GestureMode.DRAW -> {
                        if (cancelled) {
                            activePoints.clear()
                            if (rendersLiveStrokes) invalidate()
                        } else {
                            appendDrawPoints(listOf(event.strokePointAt(-1)))
                            commitActiveStroke()
                            paperListener?.onPenLifted()
                        }
                    }
                    GestureMode.ERASE -> {
                        if (!cancelled) eraseAlong(listOf(event.strokePointAt(-1)))
                        finalizeEraseRedraw()
                        if (!cancelled) paperListener?.onPenLifted()
                    }
                    GestureMode.LASSO -> {
                        lassoCapturing = false
                        if (cancelled) {
                            lassoPoints.clear()
                            invalidate()
                        } else {
                            lassoPoints.add(event.strokePointAt(-1))
                            val outline = lassoPoints.toList()
                            lassoPoints.clear()
                            completeLassoOutline(outline)
                        }
                        // No onPenLifted: a lasso gesture is chrome, not writing.
                    }
                    GestureMode.DRAG -> {
                        if (cancelled) lassoDragCancel()
                        else lassoDragFinish(event.x, event.y)
                    }
                    else -> Unit
                }
                gestureMode = GestureMode.NONE
                lastEraserPoint = null
            }
        }
        return true
    }

    // Pointer-source hover is routed to onHoverEvent, everything else generic lands in
    // onGenericMotionEvent — handle both so no hardware path can hide the pen approach.

    override fun onHoverEvent(event: MotionEvent): Boolean {
        handleStylusHover(event)
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        handleStylusHover(event)
        return super.onGenericMotionEvent(event)
    }

    /**
     * Track pen proximity for the [isPenActive] gate — on EMR panels the palm lands a
     * beat before the pen tip touches, and hover is what closes the gate during that
     * beat — and feed the raw HOVER passthrough.
     */
    private fun handleStylusHover(event: MotionEvent) {
        if (released) return
        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                penHovering = true
                penLastHoverMs = SystemClock.uptimeMillis()
                dispatchRaw(event, toolType)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                penHovering = false
                penLastHoverMs = SystemClock.uptimeMillis()
                dispatchRaw(event, toolType)
            }
        }
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        // One record covers content loaded before layout (loadStrokes/layout race).
        redrawCommitted()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Committed layer: blit the retained display list; software canvases (and the
        // pre-first-record frame) draw the vector content directly.
        if (canvas.isHardwareAccelerated && committedNode.hasDisplayList()) {
            canvas.drawRenderNode(committedNode)
        } else {
            drawCommittedContent(canvas)
        }
        // Live layer: the in-progress stroke through the same renderer as the bake.
        if (rendersLiveStrokes && gestureMode == GestureMode.DRAW && activePoints.isNotEmpty()) {
            StrokeRenderer.draw(canvas, activePoints, penColor, penWidth, penStyle, scratchPaint)
        }
        // Lasso trail (engines with hardware trails set rendersLiveTrail = false).
        if (rendersLiveTrail && lassoCapturing && lassoPoints.size >= 2) {
            val trail = Path()
            trail.moveTo(lassoPoints[0].x, lassoPoints[0].y)
            for (i in 1 until lassoPoints.size) trail.lineTo(lassoPoints[i].x, lassoPoints[i].y)
            canvas.drawPath(trail, selectionPaint)
        }
        // Drag layer: the committed record omits the selected strokes; their snapshots
        // draw translated on top. Selected host content draws live through the
        // renderer's drawObject when implemented, else as a dashed ghost of its bounds.
        if (dragActive && dragThresholdMet) {
            val save = canvas.save()
            canvas.translate(dragDx, dragDy)
            for (s in dragStrokes) {
                StrokeRenderer.draw(canvas, s.points, s.color, s.width, s.style, scratchPaint)
            }
            for ((renderer, target) in dragContentTargets) {
                if (!renderer.drawObject(canvas, target.contentId)) {
                    val b = target.bounds
                    canvas.drawRect(b.left, b.top, b.right, b.bottom, selectionPaint)
                }
            }
            canvas.restoreToCount(save)
        }
        // Selection box overlay — rides the drag delta while a drag is live.
        selection?.let { sel ->
            val box = sel.bounds.inflated(SELECTION_BOX_INFLATE_PX)
            val dx = if (dragActive && dragThresholdMet) dragDx else 0f
            val dy = if (dragActive && dragThresholdMet) dragDy else 0f
            canvas.drawRect(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy, selectionPaint)
        }
    }

    /**
     * Re-record the committed [RenderNode] and repaint. Every content mutation funnels
     * through here; nothing re-tessellates per frame. Open so deferred-bake engines can
     * interleave their overlay handoff between the record and the repaint (every
     * re-record bakes the whole model, so the hardware overlay must drop its copy of
     * the ink *before* the fresh frame presents — ordering the hardware is sensitive to).
     */
    protected open fun redrawCommitted() {
        if (recordCommitted()) invalidate()
    }

    /**
     * Record the committed content into the [RenderNode] (a display list only — cheap)
     * WITHOUT presenting a frame. Returns false before layout. Subclasses composing
     * their own [redrawCommitted] use this as the record half.
     */
    protected fun recordCommitted(): Boolean {
        val w = width
        val h = height
        if (w == 0 || h == 0) return false
        committedNode.setPosition(0, 0, w, h)
        val recordingCanvas = committedNode.beginRecording(w, h)
        try {
            drawCommittedContent(recordingCanvas)
        } finally {
            committedNode.endRecording()
        }
        return true
    }

    /**
     * Paint the full committed page: white → template (into the page rect) →
     * below-strokes host content → baked strokes → above-strokes host content.
     * Serves the node recording, the software fallback, and [renderToBitmap].
     */
    protected open fun drawCommittedContent(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        templateBitmap?.let { canvas.drawBitmap(it, null, templateDestRect(), null) }
        // Renderers get the drag exclusion set (empty outside a drag) so opted-in hosts
        // hide originals whose live copies ride the drag layer; the default overload
        // ignores it.
        for (renderer in contentRenderers) {
            if (renderer.layer == ContentLayer.BELOW_STROKES) {
                renderer.draw(canvas, dragHiddenContentIds)
            }
        }
        for (stroke in strokeList) {
            // Mid-drag, the selected strokes live in the translated drag layer instead.
            if (stroke.id in dragHiddenIds) continue
            StrokeRenderer.draw(
                canvas, stroke.points, stroke.color, stroke.width, stroke.style, scratchPaint
            )
        }
        for (renderer in contentRenderers) {
            if (renderer.layer == ContentLayer.ABOVE_STROKES) {
                renderer.draw(canvas, dragHiddenContentIds)
            }
        }
    }

    /** The template stretches into the page rect when known, else the view (see [setPageSize]). */
    private fun templateDestRect(): RectF =
        if (pageWidth > 0 && pageHeight > 0) {
            RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
        } else {
            RectF(0f, 0f, width.toFloat(), height.toFloat())
        }

    // ── Pen-activity gate marks (shared with device subclasses) ──────────────

    /**
     * Mark the stylus as on the glass for the [isPenActive] gate. Device engines whose
     * ink runs a hardware pipeline (no MotionEvents) call this from their begin callbacks.
     */
    protected fun markPenDown() {
        penDown = true
    }

    /** Mark the stylus as lifted and start the [PaperView.PEN_ACTIVE_TAIL_MS] tail. */
    protected fun markPenUp() {
        penDown = false
        penLastLiftMs = SystemClock.uptimeMillis()
    }

    /**
     * Mark the stylus as having entered hover/proximity range. For device pipelines
     * with their own proximity reporting (the Onyx SDK posts `PenActiveEvent` /
     * `PenDeactivateEvent` on its event bus — hover MotionEvents never reach the view
     * there). Level semantics: the gate stays closed until [markPenOutOfRange] plus the
     * [PaperView.PEN_ACTIVE_TAIL_MS] tail. Safe to call from any thread.
     */
    protected fun markPenInRange() {
        penHovering = true
        penLastHoverMs = SystemClock.uptimeMillis()
    }

    /** Mark the stylus as having left proximity range; starts the hover tail. */
    protected fun markPenOutOfRange() {
        penHovering = false
        penLastHoverMs = SystemClock.uptimeMillis()
    }

    // ── Stroke commit & erase ────────────────────────────────────────────────

    private fun commitActiveStroke() {
        if (activePoints.isEmpty()) return
        val points = activePoints.toList()
        activePoints.clear()
        commitCapturedStroke(points)
    }

    /**
     * Commit one captured polyline as a stroke with the armed pen config: add to the
     * model, re-record, and fire [PaperListener.onStrokeCommitted]. The single commit
     * path for this engine's own capture and for device subclasses feeding points from
     * their hardware pipelines (a single contact may legally commit several strokes —
     * the Onyx SDK can deliver more than one batch per contact).
     */
    protected fun commitCapturedStroke(points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val stroke = Stroke(
            id = UUID.randomUUID().toString(),
            points = points,
            color = penColor,
            width = penWidth,
            style = penStyle,
        )
        strokeList.add(stroke)
        modelChanged()
        bakeAfterCommit()
        paperListener?.onStrokeCommitted(stroke)
    }

    /**
     * Paint a just-committed stroke into the committed layer. Default: re-record now.
     * Deferred-bake engines (Ratta — the firmware overlay keeps showing the ink until a
     * natural boundary) override to mark the bake pending instead; the model is already
     * current either way, so saves, hit-tests and [getStrokes] never wait for the bake.
     */
    protected open fun bakeAfterCommit() {
        redrawCommitted()
    }

    /**
     * Append captured pen points, splitting the stroke around the host's exclusion
     * zones so the model never holds ink that was not painted (the [setExclusionRects]
     * contract): chrome gaps leak MotionEvents, and on hardware-ink engines the
     * firmware refuses to paint inside its disable areas. Segments outside the zones
     * commit as separate strokes; sub-2-point remnants are dropped, matching what was
     * actually painted.
     */
    private fun appendDrawPoints(points: List<StrokePoint>) {
        if (exclusionRects.isEmpty()) {
            activePoints.addAll(points)
            return
        }
        for (p in points) {
            if (exclusionRects.any { it.contains(p.x.toInt(), p.y.toInt()) }) {
                if (activePoints.size >= 2) commitCapturedStroke(activePoints.toList())
                activePoints.clear()
            } else {
                activePoints.add(p)
            }
        }
    }

    /** Start a fresh eraser sweep: the next [eraseAlong] batch won't chain to the last. */
    protected fun beginEraseSweep() {
        lastEraserPoint = null
    }

    /** Fire [PaperListener.onPenLifted] — for device subclasses' own gesture ends. */
    protected fun firePenLifted() {
        paperListener?.onPenLifted()
    }

    /** Forward one synthesized event to the host's raw passthrough listener. */
    protected fun emitRawInput(event: RawInputEvent) {
        rawInputListener?.onRawInput(event)
    }

    /**
     * Erase along one batch of eraser samples. The previous batch's last sample is
     * prepended so the sweep stays a connected polyline across events — a fast flick
     * can't jump over a stroke between batches. Protected so device subclasses can feed
     * sweeps from their hardware erase callbacks; call [beginEraseSweep] at gesture start
     * and [finalizeEraseRedraw] at gesture end.
     */
    protected fun eraseAlong(points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val sweep = lastEraserPoint?.let { prev -> ArrayList<StrokePoint>(points.size + 1).apply {
            add(prev)
            addAll(points)
        } } ?: points
        lastEraserPoint = points.last()
        val hitIds = EraseHitTest.hitStrokeIds(strokeList, sweep, eraserRadius)
        if (hitIds.isEmpty()) return
        val idSet = hitIds.toHashSet()
        // A barrel-button erase can run while a selection is active (lasso mode): if it
        // takes any selected stroke, the box no longer describes reality — dismiss.
        if (selection?.strokeIds?.any { it in idSet } == true) clearSelection()
        strokeList.removeAll { it.id in idSet }
        modelChanged()
        paperListener?.onStrokesErased(hitIds)
        throttledEraseRedraw()
    }

    private fun throttledEraseRedraw() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEraseRedrawMs >= ERASE_REDRAW_INTERVAL_MS) {
            lastEraseRedrawMs = now
            redrawCommitted()
        }
    }

    /** Flush any throttled removals at gesture end so the screen is exact on pen lift. */
    protected fun finalizeEraseRedraw() {
        lastEraseRedrawMs = SystemClock.uptimeMillis()
        redrawCommitted()
    }

    // ── Lasso gesture drive (shared with device subclasses) ──────────────────
    //
    // The full selection/drag state machine lives here once; engines whose pipeline owns
    // the pen (the Onyx raw path) capture their own points and call these entries from
    // their hardware callbacks, exactly like [commitCapturedStroke]/[eraseAlong].

    /** True from a successful [lassoTryBeginDrag] until the drag finishes or cancels —
     *  for device subclasses whose firmware must stay suppressed for the whole drag
     *  contact (the Ratta full-screen disable). */
    protected val isSelectionDragActive: Boolean get() = dragActive

    /** Whether a selection is currently active — for device subclasses deciding whether
     *  a gesture's outcome changed the overlay chrome (e.g. a tap that dismissed). */
    protected val hasActiveSelection: Boolean get() = selection != null

    /** Whether ([x], [y]) falls inside the active selection's drawn box (the inflated
     *  [Selection.bounds]) — the pre-contact test device subclasses run from their hover
     *  stream (Ratta's law-3 drag suppress). False when nothing is selected. */
    protected fun selectionBoxContains(x: Float, y: Float): Boolean {
        val sel = selection ?: return false
        if (sel.strokeIds.isEmpty() && sel.contentIds.isEmpty()) return false
        return sel.bounds.inflated(SELECTION_BOX_INFLATE_PX).contains(x, y)
    }

    /**
     * Pen-down in lasso mode: start a drag-move when ([x], [y]) lands inside the active
     * selection box. Returns false (and touches nothing) otherwise — the caller then
     * begins an outline via [lassoOutlineStart]. Snapshots the selected strokes and the
     * selected host-content bounds for the translated drag layer.
     */
    protected fun lassoTryBeginDrag(x: Float, y: Float): Boolean {
        // A second contact (pen landing during a finger drag) must not re-enter: it
        // falls through to the outline path, whose selection-dismiss cancels the drag.
        if (dragActive) return false
        if (!selectionBoxContains(x, y)) return false
        val sel = selection ?: return false
        dragActive = true
        dragThresholdMet = false
        dragStartX = x
        dragStartY = y
        dragDx = 0f
        dragDy = 0f
        dragStrokes = strokeList.filter { it.id in sel.strokeIds }
        dragContentTargets = contentRenderers.flatMap { renderer ->
            renderer.hitTargets()
                .filter { it.contentId in sel.contentIds }
                .map { renderer to it }
        }
        return true
    }

    /**
     * Drag-move sample. Once the pen travels [DRAG_THRESHOLD_DP] from the contact start
     * this fires [PaperListener.onSelectionDragStarted], hides the selected strokes from
     * the committed record, and starts the translated drag layer; below it the contact
     * is still a potential tap.
     */
    protected fun lassoDragMove(x: Float, y: Float) {
        if (!dragActive) return
        val dx = x - dragStartX
        val dy = y - dragStartY
        if (!dragThresholdMet) {
            val threshold = dragThresholdPx()
            if (dx * dx + dy * dy < threshold * threshold) return
            dragThresholdMet = true
            dragHiddenIds = selection?.strokeIds ?: emptySet()
            dragHiddenContentIds = selection?.contentIds ?: emptySet()
            onSelectionDragVisual(true)
            paperListener?.onSelectionDragStarted()
            redrawCommitted()
        }
        dragDx = dx
        dragDy = dy
        throttledLassoInvalidate()
    }

    /**
     * Pen-up on a drag contact. Below the threshold it was a tap inside the box: the
     * selection stays put. Past it: translate the selected strokes in the model, restore
     * the committed record, move the box, and report [PaperListener.onSelectionMoved] —
     * the selection remains active at its new position.
     */
    protected fun lassoDragFinish(x: Float, y: Float) {
        if (!dragActive) return
        dragActive = false
        if (!dragThresholdMet) {
            dragStrokes = emptyList()
            dragContentTargets = emptyList()
            return
        }
        dragThresholdMet = false
        val dx = x - dragStartX
        val dy = y - dragStartY
        dragDx = 0f
        dragDy = 0f
        dragStrokes = emptyList()
        dragContentTargets = emptyList()
        dragHiddenIds = emptySet()
        dragHiddenContentIds = emptySet()
        onSelectionDragVisual(false)
        val sel = selection
        if (sel == null) {
            redrawCommitted()
            return
        }
        if (sel.strokeIds.isNotEmpty()) {
            for (i in strokeList.indices) {
                val s = strokeList[i]
                if (s.id in sel.strokeIds) strokeList[i] = s.translated(dx, dy)
            }
            modelChanged()
        }
        selection = sel.copy(bounds = sel.bounds.offset(dx, dy))
        redrawCommitted()
        paperListener?.onSelectionMoved(SelectionMove(sel.strokeIds, sel.contentIds, dx, dy))
    }

    /** Cancel an in-flight drag (ACTION_CANCEL, barrel erase, tool change, teardown):
     *  the strokes return to their original spot and the selection dismisses — the
     *  [PaperListener.onSelectionDragStarted] contract's cancel signal is
     *  [PaperListener.onSelectionDismissed]. */
    protected fun lassoDragCancel() {
        if (dragActive) clearSelection()
    }

    /** Pen-down starting a NEW outline: dismiss any active selection immediately, so the
     *  user sees the box drop the moment they start lassoing elsewhere (and a tap-sized
     *  gesture outside the box needs nothing more — tap-to-dismiss falls out). */
    protected fun lassoOutlineStart() {
        clearSelection()
    }

    /**
     * A completed lasso outline (from either the base MotionEvent path or a device
     * pipeline's own capture): classify tap vs outline by gesture extent, hit-test the
     * strokes ([LassoHitTest]) and host content ([ContentRenderer.hitTargets]), and
     * create + report the selection. An empty catch leaves nothing selected.
     */
    protected fun completeLassoOutline(outline: List<StrokePoint>) {
        // Repaint regardless of outcome — the base-drawn trail must leave the screen.
        invalidate()
        val threshold = dragThresholdPx()
        val extent = Bounds.of(outline)
        if (outline.size < 3 || (extent.width < threshold && extent.height < threshold)) {
            // Tap: the previous selection was already dismissed at outline start.
            return
        }
        val strokeIds = LassoHitTest.hitStrokeIds(strokeList, outline)
        val contentTargets = contentRenderers.flatMap { it.hitTargets() }
            .filter { LassoHitTest.polygonIntersectsBounds(outline, it.bounds) }
        if (strokeIds.isEmpty() && contentTargets.isEmpty()) return
        val idSet = strokeIds.toHashSet()
        var bounds: Bounds? = null
        for (s in strokeList) {
            if (s.id in idSet) bounds = bounds?.union(s.bounds) ?: s.bounds
        }
        for (t in contentTargets) bounds = bounds?.union(t.bounds) ?: t.bounds
        val sel = Selection(
            strokeIds = idSet,
            contentIds = contentTargets.mapTo(HashSet()) { it.contentId },
            bounds = bounds ?: return,
        )
        selection = sel
        invalidate()
        paperListener?.onSelectionCreated(sel)
    }

    /**
     * A threshold-crossed drag-move became (true) / stopped being (false) the live
     * visual: device engines hook their display-pipeline tuning here — the Onyx engine
     * runs the drag in the EPD's A2 fast mode. Always balanced; called before the
     * end-of-drag repaint so the restored mode covers the final quality frame.
     */
    protected open fun onSelectionDragVisual(active: Boolean) {}

    // ── Finger interaction with the active selection ─────────────────────────

    /** What the current finger contact is doing (latched at ACTION_DOWN). DEAD = a
     *  consumed contact that stopped qualifying (palm, wander) and is ignored to UP. */
    private enum class FingerMode { NONE, DRAG, TAP, DEAD }

    private var fingerMode = FingerMode.NONE
    private var fingerDownX = 0f
    private var fingerDownY = 0f

    /**
     * The stylus-only exception: while a selection is active in lasso mode, a single
     * finger inside the box drags it (same machinery as the pen drag) and a finger tap
     * outside dismisses it. Palm-safe per the standing contract:
     * - Finger-down is refused while [isPenActive] (writing/hovering pen ⇒ that finger
     *   is a resting palm), and a pen turning active mid-drag cancels the drag.
     * - A second pointer (palm) kills the gesture; a wandering "tap" is abandoned.
     * - The dismissal commits after a [PaperView.PEN_ACTIVE_TAIL_MS] escrow and is
     *   dropped if the gate closes meanwhile — a palm micro-tap can beat the pen into
     *   hover range and must not silently throw the selection away.
     * Everything outside these cases returns false, leaving finger input to the host.
     */
    private fun handleFingerSelection(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerMode = FingerMode.NONE
                if (tool != Tool.LASSO || selection == null || isPenActive) return false
                fingerDownX = event.x
                fingerDownY = event.y
                fingerMode = if (lassoTryBeginDrag(event.x, event.y)) {
                    FingerMode.DRAG
                } else {
                    FingerMode.TAP
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (fingerMode == FingerMode.NONE) return false
                // Multi-touch = palm: kill the gesture (a cancelled drag dismisses).
                if (fingerMode == FingerMode.DRAG) lassoDragCancel()
                fingerMode = FingerMode.DEAD
                return true
            }

            MotionEvent.ACTION_MOVE -> when (fingerMode) {
                FingerMode.DRAG -> {
                    if (isPenActive) {
                        // The pen came near mid-drag: this "finger" is a palm after all.
                        lassoDragCancel()
                        fingerMode = FingerMode.DEAD
                    } else {
                        lassoDragMove(event.x, event.y)
                    }
                    return true
                }
                FingerMode.TAP -> {
                    val t = dragThresholdPx()
                    if (kotlin.math.abs(event.x - fingerDownX) > t ||
                        kotlin.math.abs(event.y - fingerDownY) > t
                    ) {
                        fingerMode = FingerMode.DEAD
                    }
                    return true
                }
                FingerMode.DEAD -> return true
                FingerMode.NONE -> return false
            }

            MotionEvent.ACTION_UP -> when (fingerMode) {
                FingerMode.DRAG -> {
                    fingerMode = FingerMode.NONE
                    if (isPenActive) lassoDragCancel() else lassoDragFinish(event.x, event.y)
                    return true
                }
                FingerMode.TAP -> {
                    fingerMode = FingerMode.NONE
                    if (!isPenActive) scheduleEscrowedDismiss()
                    return true
                }
                FingerMode.DEAD -> {
                    fingerMode = FingerMode.NONE
                    return true
                }
                FingerMode.NONE -> return false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (fingerMode == FingerMode.NONE) return false
                if (fingerMode == FingerMode.DRAG) lassoDragCancel()
                fingerMode = FingerMode.NONE
                return true
            }
        }
        return false
    }

    /** Commit a finger tap-to-dismiss after the pen-gate escrow; drop it if the pen
     *  became active meanwhile or the selection already changed. */
    private fun scheduleEscrowedDismiss() {
        val sel = selection ?: return
        postDelayed({
            if (!released && !isPenActive && selection === sel) clearSelection()
        }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    private fun dragThresholdPx(): Float = DRAG_THRESHOLD_DP * resources.displayMetrics.density

    private fun throttledLassoInvalidate() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLassoInvalidateMs >= LASSO_REFRESH_INTERVAL_MS) {
            lastLassoInvalidateMs = now
            invalidate()
        }
    }

    private fun cancelActiveGesture() {
        if (gestureMode == GestureMode.ERASE) finalizeEraseRedraw()
        if (dragActive) lassoDragCancel()
        if (lassoCapturing) {
            lassoCapturing = false
            lassoPoints.clear()
            invalidate()
        }
        gestureMode = GestureMode.NONE
        lastEraserPoint = null
        if (activePoints.isNotEmpty()) {
            activePoints.clear()
            invalidate()
        }
    }

    private fun modelChanged() {
        strokeSnapshot = ArrayList(strokeList)
    }

    // ── Raw passthrough ──────────────────────────────────────────────────────

    private fun dispatchRaw(event: MotionEvent, toolType: Int) {
        val listener = rawInputListener ?: return
        val rawTool =
            if (toolType == MotionEvent.TOOL_TYPE_ERASER) RawTool.STYLUS_ERASER else RawTool.STYLUS
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> RawAction.DOWN
            MotionEvent.ACTION_MOVE -> RawAction.MOVE
            MotionEvent.ACTION_UP -> RawAction.UP
            MotionEvent.ACTION_CANCEL -> RawAction.CANCEL
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            -> RawAction.HOVER
            else -> return
        }
        if (action == RawAction.MOVE) {
            for (i in 0 until event.historySize) {
                listener.onRawInput(rawEventAt(event, i, action, rawTool))
            }
        }
        listener.onRawInput(rawEventAt(event, -1, action, rawTool))
    }

    private fun rawEventAt(
        event: MotionEvent,
        historyIndex: Int,
        action: RawAction,
        rawTool: RawTool,
    ): RawInputEvent {
        val p = event.strokePointAt(historyIndex)
        return RawInputEvent(
            action = action,
            tool = rawTool,
            x = p.x,
            y = p.y,
            pressure = p.pressure,
            tilt = p.tilt,
            timeMillis = p.timeMillis,
        )
    }

    // ── MotionEvent → StrokePoint ────────────────────────────────────────────

    /** Sample at [historyIndex] (−1 = the current sample) as a [StrokePoint]. */
    private fun MotionEvent.strokePointAt(historyIndex: Int): StrokePoint =
        if (historyIndex < 0) {
            StrokePoint(
                x = x,
                y = y,
                pressure = pressure,
                tilt = getAxisValue(MotionEvent.AXIS_TILT),
                timeMillis = eventTime,
            )
        } else {
            StrokePoint(
                x = getHistoricalX(historyIndex),
                y = getHistoricalY(historyIndex),
                pressure = getHistoricalPressure(historyIndex),
                tilt = getHistoricalAxisValue(MotionEvent.AXIS_TILT, historyIndex),
                timeMillis = getHistoricalEventTime(historyIndex),
            )
        }

    /** All of a MOVE event's samples, oldest first: history then the current sample. */
    private fun MotionEvent.batchStrokePoints(): List<StrokePoint> {
        val out = ArrayList<StrokePoint>(historySize + 1)
        for (i in 0 until historySize) out.add(strokePointAt(i))
        out.add(strokePointAt(-1))
        return out
    }
}
