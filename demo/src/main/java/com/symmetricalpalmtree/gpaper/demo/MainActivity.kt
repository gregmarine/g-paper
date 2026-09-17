package com.symmetricalpalmtree.gpaper.demo

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.symmetricalpalmtree.gpaper.core.PageMode
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.RasterPatch
import com.symmetricalpalmtree.gpaper.core.RasterRubbing
import com.symmetricalpalmtree.gpaper.core.RawAction
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import java.util.Locale

/**
 * Demo v1 (Phase 2): full-screen paper + e-ink-first minimal controls.
 *
 * Deliberately Material-free — black-on-white, flat bordered buttons — so the same demo
 * reads correctly on BOOX/Supernote panels in Phases 3/4 and on LCD alike. Proves both
 * API directions: the stroke feed readout (data out) and a host-rendered sample object
 * that a finger tap repositions via notifyContentChanged (render in), gated by
 * [PaperView.isPenActive] exactly as the palm-rejection contract prescribes.
 */
class MainActivity : Activity() {

    private lateinit var paper: PaperView
    private lateinit var status: TextView

    private val styles = StrokeStyle.entries
    private var styleIndex = 0

    private val widths = floatArrayOf(2f, 3f, 6f, 10f, 16f)
    private var widthIndex = 1

    private val colorNames = arrayOf("Black", "DkGrey", "Grey", "Red", "Blue")
    private val colorValues = intArrayOf(
        Color.BLACK, 0xFF444444.toInt(), 0xFF888888.toInt(), Color.RED, Color.BLUE
    )
    private var colorIndex = 0

    private var penLifts = 0
    private var rawEvents = 0
    private var lastEvent = "—"

    /** Tracked from the selection callbacks: while true, the host must yield finger
     *  events to the paper view (the component owns finger drag / tap-to-dismiss). */
    private var selectionActive = false

    // ── Raster mode (0.1.32): the measurement vehicle ────────────────────────
    //
    // The demo is how the Supernote raster numbers get taken — the eraser's redraw
    // cadence, the pencil's EMR floor, the cost of an undo swap — so it carries the
    // sketching page Notesprout SN's arc 43 will ship: one pencil, one rubber, no
    // colour, no width choice, and a host-owned undo built the way the API document
    // says to build one.

    private var rasterMode = false

    /**
     * Arc 44's fifteen pencil shades: level *n* is the grey `n × 0x11`, `#000000` through
     * `#EEEEEE`, the e-paper ladder without white. Level 5 (`#555555`) is the default, and
     * it replaces the single `#505050` lead the raster vehicle carried for arc 43 — near
     * enough to it that the DARK_GRAY preview the Nomad settled is unchanged, and on the
     * ladder that the fifteen actually use.
     */
    private val rasterShadeLevels = IntArray(15) { it }
    private var rasterShadeIndex = 5

    /** Arc 44's five lead sizes, in px. 1.2 is the hairline arc 43 shipped. */
    private val rasterLeads = floatArrayOf(1.2f, 2f, 4f, 7f, 12f)
    private var rasterLeadIndex = 0

    private val rasterEraserRadiusPx = 12f

    /** ARGB of shade level [level] — a grey of `level × 0x11` in all three channels. */
    private fun shadeColor(level: Int): Int {
        val v = level * 0x11
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun shadeHex(level: Int): String =
        "#%02X%02X%02X".format(level * 0x11, level * 0x11, level * 0x11)

    /** `1.2px` / `4px` — `Locale.US` so a comma-decimal locale can't rename a lead size. */
    private fun leadLabel(px: Float): String =
        if (px == px.toInt().toFloat()) "${px.toInt()}px" else String.format(Locale.US, "%.1fpx", px)

    private val rasterShade: Int get() = rasterShadeLevels[rasterShadeIndex]
    private val rasterLead: Float get() = rasterLeads[rasterLeadIndex]

    /** What the armed pencil is, for a button face and for the status line. */
    private fun rasterPencilSummary(): String =
        "shade $rasterShade ${shadeHex(rasterShade)} · lead ${leadLabel(rasterLead)}"

    /** The stroke-mode eraser radius, restored when raster mode is turned off. */
    private var strokeEraserRadiusPx = 0f

    /**
     * Before-images, one entry per pen contact, read on a 64 px grid so a cell is read
     * **once** however many batches of a rubbing sweep cross it — reading it twice
     * would capture pixels the sweep had already lifted and make the undo a lie. Each
     * entry is both its undo and its redo: `swapPageRaster` leaves every array holding
     * what the page held (0.1.29), so one list serves in both directions.
     */
    private val rasterHistory = ArrayList<List<RasterPatch>>()
    private var rasterCursor = 0
    private var openRasterEntry: LinkedHashMap<Long, RasterPatch>? = null

    /**
     * The host-rendered sample object: a rounded box the host owns and draws. Implements
     * the optional live-drag pair — the exclusion-aware [ContentRenderer.draw] plus
     * [ContentRenderer.drawObject] — so a lasso drag moves the real box, not a ghost.
     */
    private val sampleObject = object : ContentRenderer {
        /** The object's geometry — what the transform mode (0.1.27) edits. */
        var box = OrientedBox(cx = 260f, cy = 200f, w = 480f, h = 120f, rotationDeg = 0f)
        val centerX: Float get() = box.cx
        val centerY: Float get() = box.cy
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = 3f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }

        private fun bounds() = box.aabb()

        private fun drawBox(canvas: Canvas) {
            val save = canvas.save()
            canvas.rotate(box.rotationDeg, box.cx, box.cy)
            val l = box.cx - box.w / 2f
            val t = box.cy - box.h / 2f
            boxPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 8f), 0f)
            canvas.drawRoundRect(l, t, l + box.w, t + box.h, 16f, 16f, boxPaint)
            canvas.drawText("host object — finger-tap to move", centerX, centerY - 6f, textPaint)
            canvas.drawText("(ContentRenderer, below strokes)", centerX, centerY + 30f, textPaint)
            canvas.restoreToCount(save)
        }

        override fun draw(canvas: Canvas) = drawBox(canvas)

        override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
            if ("sample-object" !in excludedContentIds) drawBox(canvas)
        }

        override fun drawObject(canvas: Canvas, contentId: String): Boolean {
            if (contentId != "sample-object") return false
            drawBox(canvas)
            return true
        }

        override fun hitTargets(): List<HitTarget> =
            listOf(HitTarget("sample-object", bounds()))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        paper = GPaper.create(this)
        paper.addContentRenderer(sampleObject)
        paper.setPaperListener(object : PaperListener {
            override fun onStrokeCommitted(stroke: Stroke) {
                lastEvent = "committed ${stroke.id.take(8)} · ${stroke.points.size} pts · ${stroke.style}"
                refreshStatus()
            }

            override fun onStrokesErased(strokeIds: List<String>) {
                lastEvent = "erased ${strokeIds.size}: ${strokeIds.joinToString { it.take(8) }}"
                refreshStatus()
            }

            override fun onPenLifted() {
                penLifts++
                // One contact, one undo entry — a mark, or a whole rubbing sweep.
                closeRasterEntry()
                refreshStatus()
            }

            /** The before-image moment: the pixels under [rect] are about to move. */
            override fun onRasterWillChange(rect: Rect) {
                captureBeforeImage(rect)
            }

            // ── Selection callbacks (Phase 5): the payloads ARE the demo ─────

            override fun onSelectionCreated(selection: Selection) {
                selectionActive = true
                lastEvent = "selected ${selection.strokeIds.size} strokes" +
                    (if (selection.contentIds.isNotEmpty()) " + ${selection.contentIds}" else "") +
                    " · bounds ${selection.bounds.left.toInt()},${selection.bounds.top.toInt()}" +
                    "→${selection.bounds.right.toInt()},${selection.bounds.bottom.toInt()}"
                refreshStatus()
            }

            override fun onSelectionDragStarted() {
                lastEvent = "selection drag started"
                refreshStatus()
            }

            override fun onSelectionMoved(move: SelectionMove) {
                // The component already translated its in-memory strokes; a real host
                // would apply the same delta to its persisted rows here. The sample
                // object is ours to move: reposition it and re-render.
                if ("sample-object" in move.contentIds) {
                    sampleObject.box = sampleObject.box.copy(
                        cx = sampleObject.box.cx + move.dx, cy = sampleObject.box.cy + move.dy,
                    )
                    paper.notifyContentChanged()
                }
                lastEvent = "moved ${move.strokeIds.size} strokes" +
                    (if (move.contentIds.isNotEmpty()) " + ${move.contentIds}" else "") +
                    " by ${move.dx.toInt()},${move.dy.toInt()}"
                refreshStatus()
            }

            override fun onSelectionDismissed() {
                selectionActive = false
                lastEvent = "selection dismissed"
                refreshStatus()
            }

            // ── Transform mode (0.1.27): the working copy follows the live box ──

            override fun onTransformChanged(contentId: String, box: OrientedBox) {
                if (contentId == "sample-object") sampleObject.box = box
                // No notifyContentChanged: the engine repaints the transform layer itself.
            }

            /** A real host persists [after] and records before → after for undo here. */
            override fun onTransformEnded(contentId: String, before: OrientedBox, after: OrientedBox) {
                if (contentId == "sample-object") sampleObject.box = after
                lastEvent = "transform ended: ${after.w.toInt()}×${after.h.toInt()} @ ${after.rotationDeg.toInt()}°" +
                    " (was ${before.w.toInt()}×${before.h.toInt()} @ ${before.rotationDeg.toInt()}°)"
                applyTransformButtons()
                refreshStatus()
            }

            /** 0.1.1: a sub-threshold stylus/finger tap inside the box. A real host would
             *  open the tapped content object here; the demo only reports it. */
            override fun onSelectionTapped(x: Float, y: Float) {
                Log.d("gpaper-demo", "selection tapped ${x.toInt()},${y.toInt()}")
                lastEvent = "selection tapped ${x.toInt()},${y.toInt()}"
                refreshStatus()
            }

            /** 0.1.5: a sub-threshold stylus tap on bare paper in LASSO with nothing
             *  selected. A real host would paste its clipboard centred here. */
            override fun onPaperTapped(x: Float, y: Float) {
                Log.d("gpaper-demo", "paper tapped ${x.toInt()},${y.toInt()}")
                lastEvent = "paper tapped ${x.toInt()},${y.toInt()}"
                refreshStatus()
            }

            /** The component changed the tool itself (smart-lasso switch to LASSO /
             *  PEN restore when the session ends) — the documented host pattern is to
             *  sync toolbar UI here, not by re-reading paper.tool in the selection
             *  callbacks (the PEN restore can land after onSelectionDismissed). */
            override fun onToolChanged(tool: Tool) {
                applyToolSelection()
                lastEvent = "component set tool: $tool"
                refreshStatus()
            }
        })
        paper.setRawInputListener { event ->
            rawEvents++
            // Refresh only at contact edges (DOWN/UP/CANCEL). MOVE **and HOVER** both
            // arrive at input rate — on EMR panels the pen hovers between every
            // stroke, and refreshing this TextView per hover sample presents app
            // frames at ~100 Hz, which drowns an e-ink display pipeline (measured on
            // the Supernote Manta: 1182 frames / 65% janky in one short writing
            // session, felt as progressive ink lag).
            if (event.action != RawAction.MOVE && event.action != RawAction.HOVER) {
                refreshStatus()
            }
        }

        // Finger tap repositions the host object (render-in proof). Stylus events fall
        // through to the engine. Palm rejection per the isPenActive contract: gate at
        // finger-DOWN *and* at finger-UP (a palm can land before the pen enters hover
        // range — by release time the pen is hovering/writing and the gate is closed),
        // and only a short, small-movement gesture counts as a tap at all.
        var tapDownX = 0f
        var tapDownY = 0f
        var tapDownMs = 0L
        var tapCandidate = false
        val tapSlopPx = dp(16).toFloat()
        paper.asView().setOnTouchListener { _, event ->
            // "Finger" = anything that isn't the stylus (some touch paths report UNKNOWN).
            val toolType = event.getToolType(0)
            val isFinger = toolType != MotionEvent.TOOL_TYPE_STYLUS &&
                toolType != MotionEvent.TOOL_TYPE_ERASER
            if (!isFinger) return@setOnTouchListener false
            // While a selection is active the COMPONENT owns finger input (drag the
            // selection, tap to dismiss) — yield, or the engine never sees the events.
            // Transform mode (0.1.27) owns it the same way (drag a handle, tap to end).
            if (selectionActive || paper.transformingContentId != null) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapCandidate = !paper.isPenActive && event.pointerCount == 1
                    tapDownX = event.x
                    tapDownY = event.y
                    tapDownMs = event.eventTime
                }
                MotionEvent.ACTION_POINTER_DOWN -> tapCandidate = false // multi-touch = palm
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.x - tapDownX) > tapSlopPx ||
                        Math.abs(event.y - tapDownY) > tapSlopPx
                    ) {
                        tapCandidate = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (tapCandidate &&
                        !paper.isPenActive &&
                        event.eventTime - tapDownMs < 300L
                    ) {
                        // Deferred tap commit: a palm micro-tap can complete a beat
                        // BEFORE the pen enters hover range (measured ~190 ms on NA5C)
                        // — no proximity signal can catch it at up-time. Hold the tap
                        // in escrow for the gate-tail duration and drop it if the pen
                        // becomes active meanwhile. A deliberate tap costs 350 ms of
                        // latency; a palm-then-write never fires.
                        val x = event.x
                        val y = event.y
                        paper.asView().postDelayed({
                            if (!paper.isPenActive) {
                                sampleObject.box = sampleObject.box.copy(cx = x, cy = y)
                                paper.notifyContentChanged()
                                lastEvent = "host object moved to ${x.toInt()},${y.toInt()}"
                                refreshStatus()
                            } else {
                                lastEvent = "tap dropped — pen became active during escrow"
                                refreshStatus()
                            }
                        }, PaperView.PEN_ACTIVE_TAIL_MS)
                    }
                    tapCandidate = false
                }
                MotionEvent.ACTION_CANCEL -> tapCandidate = false
            }
            true
        }

        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            // The counters and the last event, with a line spare for a long one to wrap.
            maxLines = 3
        }

        // Paper + the capability-notes overlay share the flexible area; the overlay
        // sits on top and is GONE until toggled (see toggleNotes).
        val paperArea = FrameLayout(this).apply {
            addView(paper.asView(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(buildNotesOverlay(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(buildToolbar())
            addView(divider())
            addView(status)
            addView(divider())
            addView(paperArea, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        // Keep the toolbar clear of the system bars — BOOX draws a status bar over the
        // window top (the Supernote panels have none, which is why Phase 2 never hit it).
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        setContentView(root)
        refreshStatus()
    }

    /**
     * EPD chrome-release contract: while the hardware writing overlay is live, ordinary
     * view invalidations (pressed states, label changes) don't reach the panel — a
     * finger-down on chrome must call [PaperView.releaseRender] first so the tap's
     * visual result shows (no-op on non-EPD engines; the overlay re-arms on the next
     * pen-down). Done in dispatchTouchEvent because button children consume touches —
     * a listener on the bar would never fire. Gated on [PaperView.isPenActive] so a palm
     * resting over the chrome mid-word can't drop the live stroke.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val toolType = ev.getToolType(0)
            val isFinger = toolType != MotionEvent.TOOL_TYPE_STYLUS &&
                toolType != MotionEvent.TOOL_TYPE_ERASER
            if (isFinger && !paper.isPenActive) {
                val paperTop = IntArray(2).also { paper.asView().getLocationInWindow(it) }[1]
                if (ev.y < paperTop) paper.releaseRender()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        paper.resumeDrawing()
    }

    override fun onDestroy() {
        paper.release()
        super.onDestroy()
    }

    // ── Capability notes (Phase 6) ───────────────────────────────────────────

    private lateinit var notesOverlay: ScrollView
    private lateinit var notesText: TextView
    private var toolBeforeNotes: Tool? = null

    private fun buildNotesOverlay(): View {
        notesText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        notesOverlay = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            addView(notesText)
        }
        return notesOverlay
    }

    /**
     * While the notes cover the paper the engine is parked in [Tool.NONE]: on the EPD
     * engines the firmware would otherwise keep inking under (BOOX: over) the overlay —
     * NONE is the one tool state that turns hardware ink fully off. Restored on close.
     */
    private fun toggleNotes() {
        if (notesOverlay.visibility == View.VISIBLE) {
            notesOverlay.visibility = View.GONE
            toolBeforeNotes?.let { paper.tool = it }
            toolBeforeNotes = null
            applyToolSelection()
        } else {
            notesText.text = capabilityNotes()
            toolBeforeNotes = paper.tool
            paper.tool = Tool.NONE
            paper.releaseRender()
            notesOverlay.visibility = View.VISIBLE
            applyToolSelection() // NONE: no tool button highlighted while notes are open
        }
    }

    private fun capabilityNotes(): String {
        val dm = resources.displayMetrics
        val engines = GPaper.registeredEngines().joinToString("\n") {
            val available = if (it.isAvailable(this)) "available" else "not available here"
            "  ${it.id}  (priority ${it.priority}, $available)"
        }
        val header = """
            |DEVICE
            |  ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}
            |  panel ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi
            |
            |REGISTERED ENGINES
            |$engines
            |
            |SELECTED ENGINE: ${paper.engineId}
        """.trimMargin()
        val engineNotes = when (paper.engineId) {
            "onyx" -> """
                |ONYX (BOOX) ENGINE
                |  Live ink: firmware raw-drawing pipeline (EPD overlay,
                |  invisible to screencap). Committed strokes: core-rendered,
                |  portable across engines.
                |  Live style mapping (committed appearance corrects on bake):
                |    PEN->PENCIL  FOUNTAIN->FOUNTAIN  MARKER->MARKER
                |    BRUSH->NEO_BRUSH  PENCIL->CHARCOAL  DASH->DASH
                |    CALLIGRAPHY->SQUARE_PEN  CROSS->CHARCOAL (baked as x-marks)
                |  Barrel-button / eraser-end erase: native, any tool.
                |  Lasso: firmware DASH trail; drag runs the panel in A2 fast mode.
                |  Pressure: normalized per device. Tilt: reported as 0
                |  (per-device scales, no SDK normalizer).
                |  Host must call OnyxEngine.register(app) from Application.onCreate
                |  and apply system-bar insets (BOOX overlays a real status bar).
            """.trimMargin()
            "ratta" -> """
                |RATTA (SUPERNOTE) ENGINE
                |  Live ink: firmware ink daemon over Binder (zero extra
                |  dependencies; EPD overlay, invisible to screencap).
                |  Committed strokes: core-rendered, portable; bake is deferred
                |  to natural boundaries (tool change, content swap).
                |  Live pen codes: PEN/MARKER/PENCIL->NEEDLE  FOUNTAIN/BRUSH->INK
                |    DASH->dash stream  CROSS->x stream  CALLIGRAPHY->15
                |  Registration compensation: +2 px (Nomad) / +3 px (Manta).
                |  Barrel-button / eraser-end: firmware suppressed from hover;
                |  software erase does the work. Lasso: firmware dash trail.
                |  Colors map to nearest firmware grey live; true ARGB on bake.
            """.trimMargin()
            else -> """
                |GENERIC ENGINE
                |  Ink runs the ordinary View pipeline — live and committed
                |  appearance are identical, and (unlike the EPD engines) the
                |  ink IS visible to screencap. All 8 styles core-rendered.
                |  Lasso: software dashed trail drawn by the shared base.
                |  Works on any Android device; no registration call needed.
            """.trimMargin()
        }
        val common = """
            |COMMON CONTRACTS
            |  Hosts own all data; stroke ids are the join key.
            |  isPenActive palm gate: writing or hovering + 350 ms tail;
            |  tap actions must re-check at finger-up and escrow the commit.
            |  Selection active: single finger drags it, finger tap dismisses.
            |  Pen gestures (opt-in, pen tool only): smart lasso = quick closed
            |  loop selects (tool auto-switches to LASSO, PEN restored on
            |  dismissal); scribble erase = dense zigzag erases touched strokes.
            |  clear() fires no erase callbacks; page turns are
            |  clearForContentSwap() + loadStrokes().
            |
            |(Notes open = tool NONE: pen input is observed, not inked.)
        """.trimMargin()
        return "$header\n\n$engineNotes\n\n$common"
    }

    // ── Raster mode: the sketching page, and the numbers it is here to take ──

    /** Undo tiles are read on this grid: one read per cell per contact. */
    private val rasterTilePx = 64

    /** True while a content call of ours is announcing changes we don't want a
     *  before-image of (`clear`, the mode flip). */
    private var suppressRasterCapture = false

    /**
     * Turn the page into pixels, or back into strokes. The mode drops content the way a
     * content swap does, so the demo clears first in both directions and starts each
     * mode from a blank page — a mode is never flipped under ink.
     *
     * Raster mode is the sketching page as the artist approved it: one `PENCIL`, the
     * rubbing eraser at 12 px on the 0.1.30 defaults, no lasso, no gestures (a hatch is
     * not a scribble, and a closed shading loop is not a selection), and no *style* to
     * choose — it is a one-tool page and stays one.
     *
     * **It does now have a shade and a lead size to choose (0.1.36).** Arc 44 gives the
     * sketch pencil fifteen greys and five leads, and this page is where they are walked:
     * the two cyclers are the only way to put a shade in front of the Ratta preview's new
     * ladder and a lead in front of the EMR floor on a real panel. They replace the single
     * fixed `#505050` at 1.2 px, and they are raster-only chrome — the stroke page's own
     * style / width / colour cyclers are untouched and still hidden here, because they
     * choose among *styles*, which this page does not have.
     */
    private fun toggleRaster() {
        if (paper.transformingContentId != null) paper.endTransform()
        suppressRasterCapture = true
        paper.clear()
        suppressRasterCapture = false
        resetRasterHistory()
        rasterMode = !rasterMode
        paper.pageMode = if (rasterMode) PageMode.RASTER else PageMode.STROKE
        if (rasterMode) {
            strokeEraserRadiusPx = paper.eraserRadius
            paper.penStyle = StrokeStyle.PENCIL
            paper.penWidth = rasterLead
            paper.penColor = shadeColor(rasterShade)
            paper.eraserRadius = rasterEraserRadiusPx
            paper.rasterRubbing = RasterRubbing()
            paper.smartLassoEnabled = false
            paper.scribbleEraseEnabled = false
            paper.removeContentRenderer(sampleObject) // plain white paper to sketch on
            if (paper.tool != Tool.ERASER) selectTool(Tool.PEN)
        } else {
            paper.penStyle = styles[styleIndex]
            paper.penWidth = widths[widthIndex]
            paper.penColor = colorValues[colorIndex]
            paper.eraserRadius = strokeEraserRadiusPx
            paper.addContentRenderer(sampleObject)
        }
        applyModeChrome()
        lastEvent = if (rasterMode) {
            "raster page: PENCIL ${rasterPencilSummary()} · rubber ${rasterEraserRadiusPx.toInt()}px"
        } else {
            "stroke page"
        }
        refreshStatus()
    }

    private fun applyModeChrome() {
        styleButton(rasterButton, selected = rasterMode)
        for (b in strokeOnlyButtons) b.visibility = if (rasterMode) View.GONE else View.VISIBLE
        for (b in rasterOnlyButtons) b.visibility = if (rasterMode) View.VISIBLE else View.GONE
        applyToolSelection()
    }

    /**
     * Read the before-image of everything [rect] covers that this contact has not read
     * already. The grid is what makes that possible: a rubbing sweep fires a will-change
     * per batch and the batches overlap at their seams, so without a per-cell record the
     * second read of a seam would capture pixels the first batch had already lifted, and
     * the undo would put back a corridor that was never there.
     */
    private fun captureBeforeImage(rect: Rect) {
        if (!rasterMode || suppressRasterCapture) return
        val entry = openRasterEntry ?: LinkedHashMap<Long, RasterPatch>().also { openRasterEntry = it }
        val x0 = (rect.left / rasterTilePx) * rasterTilePx
        val y0 = (rect.top / rasterTilePx) * rasterTilePx
        var ty = y0
        while (ty < rect.bottom) {
            var tx = x0
            while (tx < rect.right) {
                val key = (ty.toLong() shl 32) or (tx.toLong() and 0xFFFFFFFFL)
                if (!entry.containsKey(key)) {
                    // readPageRaster clips to the page, so edge cells come back short
                    // and an off-page cell comes back null.
                    paper.readPageRaster(Rect(tx, ty, tx + rasterTilePx, ty + rasterTilePx))
                        ?.let { entry[key] = it }
                }
                tx += rasterTilePx
            }
            ty += rasterTilePx
        }
    }

    /** One contact, one entry. An entry that read nothing (an off-page rub) is dropped. */
    private fun closeRasterEntry() {
        val entry = openRasterEntry ?: return
        openRasterEntry = null
        if (entry.isEmpty()) return
        while (rasterHistory.size > rasterCursor) rasterHistory.removeAt(rasterHistory.size - 1)
        rasterHistory.add(entry.values.toList())
        rasterCursor = rasterHistory.size
        val bytes = entry.values.sumOf { it.bytes }
        Log.i(TAG, "raster undo entry: ${entry.size} tiles, $bytes bytes (${rasterHistory.size} deep)")
    }

    private fun resetRasterHistory() {
        openRasterEntry = null
        rasterHistory.clear()
        rasterCursor = 0
    }

    private fun rasterUndo() {
        if (rasterCursor == 0) {
            lastEvent = "nothing to undo"
            refreshStatus()
            return
        }
        rasterCursor--
        val ms = timedSwap(rasterHistory[rasterCursor], "undo")
        lastEvent = "undo: ${rasterHistory[rasterCursor].size} tiles in $ms ms " +
            "($rasterCursor/${rasterHistory.size})"
        refreshStatus()
    }

    private fun rasterRedo() {
        if (rasterCursor >= rasterHistory.size) {
            lastEvent = "nothing to redo"
            refreshStatus()
            return
        }
        val ms = timedSwap(rasterHistory[rasterCursor], "redo")
        rasterCursor++
        lastEvent = "redo: ${rasterHistory[rasterCursor - 1].size} tiles in $ms ms " +
            "($rasterCursor/${rasterHistory.size})"
        refreshStatus()
    }

    /**
     * The swap, wall-clocked (arc 43 M4/M5). The same list serves undo and redo because
     * `swapPageRaster` leaves every array holding what the page held — so this is the
     * whole of a raster history's cost, and what it costs is a device number.
     */
    private fun timedSwap(patches: List<RasterPatch>, what: String): String {
        val bytes = patches.sumOf { it.bytes }
        val t0 = System.nanoTime()
        paper.swapPageRaster(patches)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val text = String.format("%.1f", ms)
        Log.i(TAG, "swapPageRaster ($what): ${patches.size} patches, $bytes bytes, $text ms")
        return text
    }

    /**
     * Debug door: read the whole page and swap it straight back — an identity swap whose
     * only product is its own duration. The worst case a raster history can ask for is
     * exactly this (a page-wide erase undone in one step), and it is the number that
     * decides whether an undo needs a progress indication on a given panel.
     */
    private fun swapWholePage() {
        val v = paper.asView()
        val t0 = System.nanoTime()
        val patch = paper.readPageRaster(Rect(0, 0, v.width, v.height))
        if (patch == null) {
            lastEvent = "no raster page to swap"
            refreshStatus()
            return
        }
        val readMs = String.format("%.1f", (System.nanoTime() - t0) / 1_000_000.0)
        val swapMs = timedSwap(listOf(patch), "whole page")
        Log.i(TAG, "whole-page read ${patch.bytes} bytes in $readMs ms")
        lastEvent = "page swap: ${patch.rect.width()}×${patch.rect.height()}, " +
            "read $readMs ms, swap $swapMs ms"
        refreshStatus()
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────

    private lateinit var penButton: TextView
    private lateinit var eraserButton: TextView
    private lateinit var lassoButton: TextView
    private lateinit var transformButton: TextView
    private lateinit var lockButton: TextView
    private lateinit var rasterButton: TextView
    private var transformLocked = false

    /** Chrome that belongs to a stroke page only — hidden while the page is pixels
     *  (one pencil, one rubber: nothing here has a meaning there). */
    private val strokeOnlyButtons = mutableListOf<TextView>()

    /** Chrome that only a raster page has: the pencil's shade and lead (0.1.36), the
     *  host-owned undo and the swap timer. */
    private val rasterOnlyButtons = mutableListOf<TextView>()

    private fun applyTransformButtons() {
        val active = paper.transformingContentId != null
        transformButton.text = if (active) "Done" else "Xform"
        styleButton(transformButton, selected = active)
        styleButton(lockButton, selected = transformLocked)
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        penButton = toolbarButton("Pen") { selectTool(Tool.PEN) }
        eraserButton = toolbarButton("Eraser") { selectTool(Tool.ERASER) }
        lassoButton = toolbarButton("Lasso") { selectTool(Tool.LASSO) }

        val styleButton = toolbarButton("Style: PEN") { }
        styleButton.setOnClickListener {
            styleIndex = (styleIndex + 1) % styles.size
            paper.penStyle = styles[styleIndex]
            styleButton.text = "Style: ${styles[styleIndex]}"
        }

        val widthButton = toolbarButton("W: ${widths[widthIndex].toInt()}") { }
        widthButton.setOnClickListener {
            widthIndex = (widthIndex + 1) % widths.size
            paper.penWidth = widths[widthIndex]
            widthButton.text = "W: ${widths[widthIndex].toInt()}"
        }

        val colorButton = toolbarButton("Color: Black") { }
        colorButton.setOnClickListener {
            colorIndex = (colorIndex + 1) % colorValues.size
            paper.penColor = colorValues[colorIndex]
            colorButton.text = "Color: ${colorNames[colorIndex]}"
        }

        // Pen-gesture recognizers (Phase 9) — opt-in flags, selected-state = enabled.
        val smartLassoButton = toolbarButton("SmartL") { }
        smartLassoButton.setOnClickListener {
            paper.smartLassoEnabled = !paper.smartLassoEnabled
            styleButton(smartLassoButton, selected = paper.smartLassoEnabled)
            lastEvent = "smart lasso ${if (paper.smartLassoEnabled) "ON" else "OFF"}" +
                " (pen tool: quick closed loop around content selects)"
            refreshStatus()
        }

        val scribbleButton = toolbarButton("Scrib") { }
        scribbleButton.setOnClickListener {
            paper.scribbleEraseEnabled = !paper.scribbleEraseEnabled
            styleButton(scribbleButton, selected = paper.scribbleEraseEnabled)
            lastEvent = "scribble erase ${if (paper.scribbleEraseEnabled) "ON" else "OFF"}" +
                " (pen tool: dense zigzag over content erases)"
            refreshStatus()
        }

        val clearButton = toolbarButton("Clear") {
            // On a raster page clear() announces the whole page as about to change;
            // reading a before-image of it would be an 18 MB copy for a history that
            // is being thrown away in the next line anyway.
            suppressRasterCapture = true
            paper.clear()
            suppressRasterCapture = false
            resetRasterHistory()
            lastEvent = "cleared (no erase callbacks — by contract)"
            refreshStatus()
        }

        val notesButton = toolbarButton("Notes") { toggleNotes() }

        // Transform mode (0.1.27) on the sample object: Xform enters (arming LASSO — the
        // mode requires it) or, while active, is the host's Done; Lock flips the aspect
        // lock of the running mode.
        transformButton = toolbarButton("Xform") {
            if (paper.transformingContentId != null) {
                paper.endTransform()
            } else {
                selectTool(Tool.LASSO)
                paper.beginTransform("sample-object", sampleObject.box, transformLocked, minSizePx = dp(24).toFloat())
                lastEvent = "transform began on sample-object"
                refreshStatus()
            }
            applyTransformButtons()
        }
        lockButton = toolbarButton("Lock") {
            transformLocked = !transformLocked
            paper.setTransformAspectLocked(transformLocked)
            applyTransformButtons()
        }

        // ── Raster mode (0.1.32) ────────────────────────────────────────────
        rasterButton = toolbarButton("Raster") { toggleRaster() }
        val undoButton = toolbarButton("Undo") { rasterUndo() }
        val redoButton = toolbarButton("Redo") { rasterRedo() }
        val swapPageButton = toolbarButton("Swap pg") { swapWholePage() }

        // The pencil's shade and lead (0.1.36) — arc 44's walk surface. Each tap steps one
        // and wraps, like the stroke page's cyclers, and the face carries the current value
        // because on a walk the question is always "which one am I looking at now". Both
        // push straight into the live pen: `penColor` / `penWidth` re-arm the firmware on
        // Ratta, so a pick takes effect on the very next mark without a tool boundary.
        val shadeButton = toolbarButton("Shade $rasterShade ${shadeHex(rasterShade)}") { }
        shadeButton.setOnClickListener {
            rasterShadeIndex = (rasterShadeIndex + 1) % rasterShadeLevels.size
            paper.penColor = shadeColor(rasterShade)
            shadeButton.text = "Shade $rasterShade ${shadeHex(rasterShade)}"
            lastEvent = "pencil ${rasterPencilSummary()}"
            refreshStatus()
        }

        val leadButton = toolbarButton("Lead ${leadLabel(rasterLead)}") { }
        leadButton.setOnClickListener {
            rasterLeadIndex = (rasterLeadIndex + 1) % rasterLeads.size
            paper.penWidth = rasterLead
            leadButton.text = "Lead ${leadLabel(rasterLead)}"
            lastEvent = "pencil ${rasterPencilSummary()}"
            refreshStatus()
        }

        strokeOnlyButtons += listOf(
            lassoButton, styleButton, widthButton, colorButton,
            smartLassoButton, scribbleButton, transformButton, lockButton,
        )
        rasterOnlyButtons += listOf(shadeButton, leadButton, undoButton, redoButton, swapPageButton)

        for (b in listOf(penButton, eraserButton, lassoButton, styleButton, widthButton, colorButton, smartLassoButton, scribbleButton, clearButton, transformButton, lockButton, rasterButton, shadeButton, leadButton, undoButton, redoButton, swapPageButton, notesButton)) {
            bar.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }

        applyToolSelection()
        applyTransformButtons()
        applyModeChrome()
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
    }

    private fun selectTool(tool: Tool) {
        // Picking a tool while the notes cover the paper implies "back to drawing" —
        // drop the overlay and its saved tool instead of restoring a stale one later.
        if (notesOverlay.visibility == View.VISIBLE) {
            notesOverlay.visibility = View.GONE
            toolBeforeNotes = null
        }
        paper.tool = tool
        applyToolSelection()
    }

    private fun applyToolSelection() {
        styleButton(penButton, selected = paper.tool == Tool.PEN)
        styleButton(eraserButton, selected = paper.tool == Tool.ERASER)
        styleButton(lassoButton, selected = paper.tool == Tool.LASSO)
    }

    private fun toolbarButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            styleButton(this, selected = false)
        }

    /** E-ink-first button chrome: flat 2px black border; selected = solid black, white text. */
    private fun styleButton(button: TextView, selected: Boolean) {
        button.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setStroke(dp(2), Color.BLACK)
            setColor(if (selected) Color.BLACK else Color.WHITE)
        }
        button.setTextColor(if (selected) Color.WHITE else Color.BLACK)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    /** True while a status update is parked waiting for the pen gate to open. */
    private var statusDeferred = false

    private val statusFlush = object : Runnable {
        override fun run() {
            if (paper.isPenActive) {
                // Still writing/hovering — check again shortly; stay frame-silent.
                status.postDelayed(this, 250L)
            } else {
                statusDeferred = false
                applyStatusText()
            }
        }
    }

    /**
     * Present NO app frames while the pen is active. On Supernote, pixels under
     * firmware overlay ink are frozen against app updates — every frame presented
     * mid-writing pays a masking cost that grows with the accumulated unbaked ink
     * (the bake is deferred to natural boundaries by design), felt as progressively
     * lagging ink on the Nomad. The reference app never hit it because its hosts
     * present no frames mid-writing; this defer restores that discipline — the
     * status catches up ~350 ms after the pen leaves.
     */
    private fun refreshStatus() {
        if (paper.isPenActive) {
            if (!statusDeferred) {
                statusDeferred = true
                status.postDelayed(statusFlush, PaperView.PEN_ACTIVE_TAIL_MS)
            }
            return
        }
        applyStatusText()
    }

    private fun applyStatusText() {
        val head = if (rasterMode) {
            "engine:${paper.engineId} · RASTER · undo:$rasterCursor/${rasterHistory.size} · " +
                "penLifts:$penLifts · raw:$rawEvents"
        } else {
            "engine:${paper.engineId} · strokes:${paper.getStrokes().size} · " +
                "penLifts:$penLifts · raw:$rawEvents"
        }
        status.text = "$head\n$lastEvent"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "gpaper-demo"
    }
}
