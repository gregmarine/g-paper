package com.symmetricalpalmtree.gpaper.core.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import android.util.Log
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
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
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
        const val TAG = "CanvasPaperView"

        /** Redraw at most this often while the eraser sweeps (erase-path performance rule). */
        const val ERASE_REDRAW_INTERVAL_MS = 60L

        /** Default eraser hit radius in px, mirrored from the reference engines. */
        const val DEFAULT_ERASER_RADIUS_PX = 15f
    }

    /** What the current stylus contact is doing; latched at ACTION_DOWN. */
    private enum class GestureMode { NONE, DRAW, ERASE, OBSERVE }

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

    override fun loadStrokes(strokes: List<Stroke>) {
        strokeList.clear()
        strokeList.addAll(strokes)
        modelChanged()
        redrawCommitted()
    }

    override fun addStrokes(strokes: List<Stroke>) {
        strokeList.addAll(strokes)
        modelChanged()
        redrawCommitted()
    }

    override fun removeStrokes(ids: Collection<String>) {
        val idSet = ids as? Set<String> ?: ids.toHashSet()
        if (strokeList.removeAll { it.id in idSet }) {
            modelChanged()
            redrawCommitted()
        }
    }

    override fun getStrokes(): List<Stroke> = strokeSnapshot

    override fun clear() {
        activePoints.clear()
        strokeList.clear()
        modelChanged()
        redrawCommitted()
    }

    override fun clearForContentSwap() {
        // Model drops now; pixels stay — no re-record, no invalidate. The next
        // loadStrokes() (or other content call) swaps the screen in one repaint.
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

    // ── PaperView: selection (Phase 5) ───────────────────────────────────────

    override fun clearSelection() {
        // Phase 5: no selection exists yet on this engine, so nothing to dismiss.
    }

    override fun setSelection(strokeIds: Set<String>, contentIds: Set<String>, bounds: Bounds) {
        Log.w(TAG, "setSelection ignored — selection lands in Phase 5")
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
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER
        // Stylus-only: finger (and mouse) events pass through to the host untouched.
        if (!isStylus || released) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Host chrome zones never start ink; let the platform route the event.
                if (exclusionRects.any { it.contains(event.x.toInt(), event.y.toInt()) }) {
                    return false
                }
                markPenDown()
                gestureMode = when {
                    tool == Tool.NONE || tool == Tool.LASSO -> GestureMode.OBSERVE
                    toolType == MotionEvent.TOOL_TYPE_ERASER ||
                        tool == Tool.ERASER ||
                        (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    -> GestureMode.ERASE
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
        for (renderer in contentRenderers) {
            if (renderer.layer == ContentLayer.BELOW_STROKES) renderer.draw(canvas)
        }
        for (stroke in strokeList) {
            StrokeRenderer.draw(
                canvas, stroke.points, stroke.color, stroke.width, stroke.style, scratchPaint
            )
        }
        for (renderer in contentRenderers) {
            if (renderer.layer == ContentLayer.ABOVE_STROKES) renderer.draw(canvas)
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

    private fun cancelActiveGesture() {
        if (gestureMode == GestureMode.ERASE) finalizeEraseRedraw()
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
