package com.symmetricalpalmtree.gpaper.demo

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.RawAction
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget

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

    /**
     * The host-rendered sample object: a rounded box the host owns and draws. Implements
     * the optional live-drag pair — the exclusion-aware [ContentRenderer.draw] plus
     * [ContentRenderer.drawObject] — so a lasso drag moves the real box, not a ghost.
     */
    private val sampleObject = object : ContentRenderer {
        var centerX = 260f
        var centerY = 200f
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

        private fun bounds() = Bounds(centerX - 240f, centerY - 60f, centerX + 240f, centerY + 60f)

        private fun drawBox(canvas: Canvas) {
            val b = bounds()
            boxPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 8f), 0f)
            canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, 16f, 16f, boxPaint)
            canvas.drawText("host object — finger-tap to move", centerX, centerY - 6f, textPaint)
            canvas.drawText("(ContentRenderer, below strokes)", centerX, centerY + 30f, textPaint)
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
                refreshStatus()
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
                    sampleObject.centerX += move.dx
                    sampleObject.centerY += move.dy
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
        })
        paper.setRawInputListener { event ->
            rawEvents++
            // Refresh only at gesture edges — the MOVE stream arrives at input rate.
            if (event.action != RawAction.MOVE) refreshStatus()
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
            if (selectionActive) return@setOnTouchListener false
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
                                sampleObject.centerX = x
                                sampleObject.centerY = y
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
            maxLines = 2
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
            |  clear() fires no erase callbacks; page turns are
            |  clearForContentSwap() + loadStrokes().
            |
            |(Notes open = tool NONE: pen input is observed, not inked.)
        """.trimMargin()
        return "$header\n\n$engineNotes\n\n$common"
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────

    private lateinit var penButton: TextView
    private lateinit var eraserButton: TextView
    private lateinit var lassoButton: TextView

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

        val clearButton = toolbarButton("Clear") {
            paper.clear()
            lastEvent = "cleared (no erase callbacks — by contract)"
            refreshStatus()
        }

        val notesButton = toolbarButton("Notes") { toggleNotes() }

        for (b in listOf(penButton, eraserButton, lassoButton, styleButton, widthButton, colorButton, clearButton, notesButton)) {
            bar.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }

        applyToolSelection()
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

    private fun refreshStatus() {
        status.text =
            "engine:${paper.engineId} · strokes:${paper.getStrokes().size} · " +
                "penLifts:$penLifts · raw:$rawEvents\n$lastEvent"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
