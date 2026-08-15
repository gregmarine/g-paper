package com.symmetricalpalmtree.gpaper.onyx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.symmetricalpalmtree.gpaper.core.RawAction
import com.symmetricalpalmtree.gpaper.core.RawInputEvent
import com.symmetricalpalmtree.gpaper.core.RawTool
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.canvas.CanvasPaperView
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle

/**
 * The BOOX (Onyx) engine: live ink is painted by the firmware through the SDK's
 * raw-drawing pipeline (`TouchHelper` → `EpdController`), while the committed layer,
 * stroke model, erase hit-testing, template, and host-content rendering are all the
 * shared [CanvasPaperView] base — the Notesprout sibling-copy trap is dead by design.
 *
 * Ported device knowledge (see Notesprout `docs/drawing-engine.md`):
 * - **First-stroke fast-mode pin** ([HWR_APP_SCOPE]): the app-scope handwriting
 *   waveform removes the 1–2 s first-stroke lag. Applied when the pen pipeline arms for
 *   the pen tool; cleared in [closeRawDrawingIfOwner]. The scope is registered with the
 *   EPD service **by name, not by process** — it survives process death, hence the
 *   [OnyxEngine.register] process-start healing and the release-on-window-hidden rule.
 * - **Process-global pen ownership** ([penOwner]): only one view can own the raw
 *   pipeline; Android runs the incoming screen's open before the outgoing screen's
 *   close, so every close routes through [closeRawDrawingIfOwner].
 * - **Overlay handoffs**: `setRawDrawingRenderEnabled(false)` is a lightweight toggle
 *   that does NOT clear the hardware buffer — every content swap needs the
 *   render-off → repaint → `handwritingRepaint` → re-arm dance ([epdRepaintHandoff]),
 *   and erase gestures repaint only at gesture end (never per move — full-panel flash).
 * - **updList sizing**: [EPD_UPDATE_LIST_SIZE] suppresses mid-session auto-GC16.
 * - **Barrel button / stylus eraser end**: surfaces as the SDK's raw *erasing*
 *   callbacks while the raw path is enabled — erase works regardless of the armed tool.
 *
 * Live ink styling maps [StrokeStyle] to the nearest firmware style
 * ([liveStyleCode] — all nine firmware styles are device-proven no-restart and
 * fast-mode-safe on the Tier-1 fleet); the committed appearance is core-rendered and
 * portable. Engine failures after construction are loud (`Log.w`), never silently
 * swapped for another engine.
 */
internal class OnyxPaperView(context: Context) : CanvasPaperView(context) {

    private companion object {
        const val TAG = "GPaperOnyx"

        /** Suppresses EPD hardware auto-GC16 refresh mid-session; quality refreshes are
         *  driven explicitly via `handwritingRepaint` at the handoff points. */
        const val EPD_UPDATE_LIST_SIZE = 512

        /** App-scope tag for the handwriting fast-mode pin. Registered by NAME with the
         *  EPD service; governs the whole panel until cleared (see class KDoc). */
        const val HWR_APP_SCOPE = "gpaper_hwr"

        /** Firmware lasso-trail width in px (reference value, device-proven with the
         *  DASH style on the Tier-1 fleet). The trail is chrome, not ink — independent
         *  of the armed pen width. */
        const val LASSO_TRAIL_WIDTH = 3f

        /** Retry delay for the tap-away dismissal repaint — the immediate repaint can
         *  race the SDK's end-of-contact processing and be eaten. */
        const val DISMISS_REPAINT_RETRY_MS = 250L

        /**
         * Process-global owner of the single Onyx raw-drawing pipeline. Every successful
         * [openRawDrawing] claims it; every close routes through [closeRawDrawingIfOwner],
         * which skips the global close when a newer view has already claimed the pipeline
         * (a superseded view must never tear down the live view's session). Main-thread
         * only; `@Volatile` is defensive.
         */
        @Volatile
        var penOwner: OnyxPaperView? = null
    }

    private val touchHelper: TouchHelper by lazy { TouchHelper.create(this, rawInputCallback) }
    private var isSetup = false

    /**
     * Pen-proximity feed for the [isPenActive] gate — the palm lands a beat before the
     * pen tip, so the gate must close during the approach. With the raw pipeline open,
     * stylus hover reaches the view only when `TouchHelper.setPostInputEvent(true)` is
     * set (default off — [openRawDrawing] enables it; bytecode-verified on 1.5.4, and
     * without it there is NO proximity signal at all on real hardware, verified NA5C).
     * With it on, `RawInputReader` posts `PenActiveEvent` when the pen enters EMR range
     * and `PenDeactivateEvent` when it leaves (100 ms keep-alive timeout) on the SDK's
     * internal event bus — clean level semantics, subscribed here. Events arrive on the
     * raw input thread; the base gate fields are volatile, so no marshalling.
     */
    private val penProximitySubscriber = object {
        @org.greenrobot.eventbus.Subscribe
        fun onPenActiveEvent(@Suppress("UNUSED_PARAMETER") event: com.onyx.android.sdk.pen.event.PenActiveEvent) {
            markPenInRange()
        }

        @org.greenrobot.eventbus.Subscribe
        fun onPenDeactivateEvent(@Suppress("UNUSED_PARAMETER") event: com.onyx.android.sdk.pen.event.PenDeactivateEvent) {
            markPenOutOfRange()
        }
    }

    private var busSubscribed = false

    private fun subscribePenProximity() {
        if (busSubscribed) return
        try {
            TouchHelper.register(penProximitySubscriber)
            busSubscribed = true
        } catch (t: Throwable) {
            Log.w(TAG, "pen-proximity bus subscribe failed — palm gate loses the approach window", t)
        }
    }

    private fun unsubscribePenProximity() {
        if (!busSubscribed) return
        busSubscribed = false
        try {
            TouchHelper.unregister(penProximitySubscriber)
        } catch (t: Throwable) {
            Log.w(TAG, "pen-proximity bus unsubscribe failed", t)
        }
    }

    /** Pressure normalizer — per-device (4095 or 4096 on the surveyed fleet), never hardcode. */
    private val maxTouchPressure: Float by lazy {
        val max = try {
            EpdController.getMaxTouchPressure()
        } catch (t: Throwable) {
            Log.w(TAG, "getMaxTouchPressure failed; pressure will report 1.0", t)
            0f
        }
        if (max > 0f) max else 0f
    }

    override val engineId: String get() = OnyxEngine.ENGINE_ID

    // ── Tool & pen configuration → firmware ──────────────────────────────────

    override var tool: Tool
        get() = super.tool
        set(value) {
            val changed = super.tool != value
            super.tool = value
            if (changed) applyToolState()
        }

    override var penColor: Int
        get() = super.penColor
        set(value) {
            super.penColor = value
            rearmPenIfLive()
        }

    override var penWidth: Float
        get() = super.penWidth
        set(value) {
            super.penWidth = value
            rearmPenIfLive()
        }

    override var penStyle: StrokeStyle
        get() = super.penStyle
        set(value) {
            super.penStyle = value
            rearmPenIfLive()
        }

    override var eraserRadius: Float
        get() = super.eraserRadius
        set(value) {
            super.eraserRadius = value
            if (isSetup && penOwner === this && tool == Tool.ERASER) {
                touchHelper.setEraserRawDrawingEnabled(true, (value * 2).toInt())
            }
        }

    /**
     * Live-ink firmware style for a [StrokeStyle] (the mapping table in `StrokeStyle`
     * KDoc / `docs/api.md`). CROSS has no firmware x-stream; CHARCOAL is the nearest
     * live texture — the bake corrects to true x-marks.
     */
    private fun liveStyleCode(style: StrokeStyle): Int = when (style) {
        StrokeStyle.PEN -> TouchHelper.STROKE_STYLE_PENCIL
        StrokeStyle.FOUNTAIN -> TouchHelper.STROKE_STYLE_FOUNTAIN
        StrokeStyle.MARKER -> TouchHelper.STROKE_STYLE_MARKER
        StrokeStyle.BRUSH -> TouchHelper.STROKE_STYLE_NEO_BRUSH
        StrokeStyle.PENCIL -> TouchHelper.STROKE_STYLE_CHARCOAL
        StrokeStyle.CALLIGRAPHY -> TouchHelper.STROKE_STYLE_SQUARE_PEN
        StrokeStyle.DASH -> TouchHelper.STROKE_STYLE_DASH
        StrokeStyle.CROSS -> TouchHelper.STROKE_STYLE_CHARCOAL
    }

    /** Arm the firmware overlay with the pen's style/width/ink. `setStrokeStyle` needs
     *  no restart and takes effect on the very next stroke (device-proven). */
    private fun applyPenStyle() {
        if (!isSetup || penOwner !== this) return
        touchHelper.setStrokeStyle(liveStyleCode(penStyle))
        touchHelper.setStrokeWidth(penWidth)
        // Explicit color always (NoteAir5C's color panel defaults to non-black).
        touchHelper.setStrokeColor(penColor)
    }

    /** Arm the firmware's dashed style as the live lasso trail (black, chrome width) —
     *  the BOOX half of the hardware-trail design; device-proven no-restart and
     *  fast-mode-safe like every firmware style. */
    private fun applyLassoTrailStyle() {
        if (!isSetup || penOwner !== this) return
        touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_DASH)
        touchHelper.setStrokeWidth(LASSO_TRAIL_WIDTH)
        touchHelper.setStrokeColor(android.graphics.Color.BLACK)
    }

    private fun rearmPenIfLive() {
        if (isSetup && penOwner === this && tool == Tool.PEN) applyPenStyle()
    }

    /**
     * (Re-)arm the raw pipeline for the current tool. PEN: raw on, style armed, render
     * managed by the SDK (re-enabled at each begin callback). ERASER: raw on with the
     * SDK eraser cursor, render OFF so bitmap erase results are visible (phantom strokes
     * otherwise). LASSO: raw on with the DASH trail style armed and render OFF between
     * gestures — enabled per-outline at the begin callback, which is what lets a
     * drag-move start inside the selection box without painting a trail blip (the begin
     * callback decides drag-vs-outline before render is touched; no hover suppression
     * needed, unlike Ratta's law 3). NONE: raw off — the stylus arrives as ordinary
     * MotionEvents and the base class observes it.
     *
     * Deliberately does NOT touch the app-scope fast-mode pin — that is applied only at
     * the open and tool-change boundaries ([applyToolState], [openRawDrawing]) and
     * cleared in [closeRawDrawingIfOwner]; keeping it out of the per-handoff path is a
     * containment rule inherited from the reference engine.
     */
    private fun armRawForCurrentTool() {
        if (!isSetup || penOwner !== this) return
        when (tool) {
            Tool.PEN -> {
                touchHelper.setEraserRawDrawingEnabled(false, 0)
                applyPenStyle()
                touchHelper.setRawDrawingEnabled(true)
            }
            Tool.ERASER -> {
                touchHelper.setEraserRawDrawingEnabled(true, (eraserRadius * 2).toInt())
                touchHelper.setRawDrawingEnabled(true)
                // Release the overlay render immediately, before any erase logic — the
                // overlay otherwise hides the erase results (strokes appear to survive).
                touchHelper.setRawDrawingRenderEnabled(false)
                invalidate()
            }
            Tool.LASSO -> {
                touchHelper.setEraserRawDrawingEnabled(false, 0)
                applyLassoTrailStyle()
                touchHelper.setRawDrawingEnabled(true)
                touchHelper.setRawDrawingRenderEnabled(false)
                invalidate()
            }
            Tool.NONE -> {
                touchHelper.setEraserRawDrawingEnabled(false, 0)
                touchHelper.setRawDrawingEnabled(false)
                touchHelper.setRawDrawingRenderEnabled(false)
                invalidate()
            }
        }
    }

    /** Tool-change boundary: drop any in-flight raw lasso gesture, re-arm the pipeline,
     *  and (re)apply the fast-mode pin when the pen tool arms. */
    private fun applyToolState() {
        cancelRawLasso("applyToolState")
        armRawForCurrentTool()
        if (isSetup && penOwner === this && tool == Tool.PEN) applyHandwritingFastMode()
    }

    /**
     * First-stroke fix: pin the app into the fast handwriting waveform so the first
     * stroke after an open / content swap pays no GC→handwriting mode switch (1–2 s on
     * BOOX; app-scope proven the sole fix by the reference device sweep). Stays active
     * across content swaps; cleared when the pipeline is released.
     */
    private fun applyHandwritingFastMode() {
        EpdController.applyAppScopeUpdate(
            HWR_APP_SCOPE, true, false, UpdateMode.HAND_WRITING_REPAINT_MODE, 0
        )
    }

    // ── Raw lasso driver (hardware DASH trail; gesture fed to the shared base) ──

    /** In-flight raw lasso gesture state. The move stream is the fallback geometry; the
     *  batched list (which can arrive in several batches per contact, before the end
     *  callback) is authoritative when present — mirroring the stroke path. */
    private var rawLassoCapture = false
    private var rawDragActive = false
    private var rawLassoRenderOn = false
    private val rawLassoMovePoints = ArrayList<StrokePoint>()
    private val rawLassoListPoints = ArrayList<StrokePoint>()

    /** Whether this outline contact dismissed a selection at pen-down. The dismissal
     *  frame is generated DURING the raw contact, whose session withholds the panel
     *  update — and the pen-up invalidate produces an identical, damage-free frame the
     *  panel ignores. A tap-away therefore needs an explicit repaint at contact end
     *  (measured NA5C: without it the box lingered until an eventual refresh). */
    private var rawLassoDismissedAtDown = false

    /**
     * Pen-down in lasso mode on the raw path: start a drag (inside the selection box) or
     * a new outline. The overlay render stays OFF here either way — for an outline it is
     * enabled at the **first move sample** ([enableRawLassoRenderOnFirstMove]), not at
     * pen-down: a tap then never turns the overlay on, so the dismissal frame from
     * [lassoOutlineStart] reaches the panel immediately (with the overlay enabled at
     * down, the box visibly lingered seconds on a tap-away — the micro-contact's wipe
     * repaint raced the SDK's end-of-contact handling and was eaten; measured NA5C).
     * A real outline just loses its first millimeter of trail, which is chrome anyway.
     */
    private fun beginRawLasso(touchPoint: TouchPoint) {
        rawLassoMovePoints.clear()
        rawLassoListPoints.clear()
        if (lassoTryBeginDrag(touchPoint.x, touchPoint.y)) {
            rawDragActive = true
            return
        }
        rawLassoCapture = true
        rawLassoRenderOn = false
        rawLassoDismissedAtDown = hasActiveSelection
        lassoOutlineStart()
        rawLassoMovePoints.add(touchPoint.toStrokePoint())
    }

    /** First outline move: turn the overlay render on — the firmware paints the DASH
     *  trail from here until pen-up. */
    private fun enableRawLassoRenderOnFirstMove() {
        if (rawLassoRenderOn || !isSetup) return
        rawLassoRenderOn = true
        touchHelper.setRawDrawingRenderEnabled(true)
    }

    /** Pen-up on a raw lasso contact: finish the drag, or wipe the trail and hand the
     *  captured outline to the shared classifier. */
    private fun endRawLasso(touchPoint: TouchPoint) {
        if (rawDragActive) {
            rawDragActive = false
            lassoDragFinish(touchPoint.x, touchPoint.y)
            return
        }
        if (!rawLassoCapture) return
        rawLassoCapture = false
        val outline = ArrayList<StrokePoint>(
            if (rawLassoListPoints.isNotEmpty()) rawLassoListPoints else rawLassoMovePoints
        )
        outline.add(touchPoint.toStrokePoint())
        rawLassoMovePoints.clear()
        rawLassoListPoints.clear()
        // Wipe BEFORE the selection box appears (the proven smart-lasso wipe): render
        // off + an app frame, then a handwriting repaint so the trail pixels actually
        // leave the e-ink. Render stays off until the next outline's first move. A
        // gesture that never enabled the render painted nothing — no wipe; but if its
        // pen-down dismissed a selection (tap-away), the panel still needs an explicit
        // repaint now that the contact is over (see rawLassoDismissedAtDown).
        val dismissedAtDown = rawLassoDismissedAtDown
        rawLassoDismissedAtDown = false
        if (rawLassoRenderOn) {
            rawLassoRenderOn = false
            wipeRawLassoTrail()
        } else if (dismissedAtDown) {
            presentDismissalRepaint()
        }
        completeLassoOutline(outline)
    }

    /** Push the box-dismissal pixels to the panel after a tap-away contact: one repaint
     *  now plus one delayed retry — the immediate one can race the SDK's end-of-contact
     *  processing and be eaten (idempotent; unchanged pixels repaint invisibly). */
    private fun presentDismissalRepaint() {
        invalidate()
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
        }
        postDelayed({
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
        }, DISMISS_REPAINT_RETRY_MS)
    }

    private fun wipeRawLassoTrail() {
        if (!isSetup) return
        touchHelper.setRawDrawingRenderEnabled(false)
        invalidate()
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
        }
    }

    /** Drop any in-flight raw lasso capture/drag (barrel erase, tool change, teardown). */
    private fun cancelRawLasso(caller: String) {
        if (!rawLassoCapture && !rawDragActive) return
        Log.i(TAG, "raw lasso cancelled ($caller)")
        rawLassoMovePoints.clear()
        rawLassoListPoints.clear()
        rawLassoDismissedAtDown = false
        if (rawDragActive) {
            rawDragActive = false
            lassoDragCancel()
        }
        if (rawLassoCapture) {
            rawLassoCapture = false
            if (rawLassoRenderOn) {
                rawLassoRenderOn = false
                wipeRawLassoTrail()
            }
        }
    }

    /** A2 fast mode for the drag-move visual (reference-proven): responsive greyscale
     *  motion while the drag runs; back to the device's default — never a mode we
     *  choose — before the final quality frame presents. */
    override fun onSelectionDragVisual(active: Boolean) {
        if (active) {
            EpdController.setViewDefaultUpdateMode(this, UpdateMode.GU_FAST)
        } else {
            EpdController.resetViewUpdateMode(this)
        }
    }

    // ── Raw input callback — the ink path (SDK ink never reaches onTouchEvent) ──

    private val rawInputCallback = object : RawInputCallback() {

        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            // Before anything else: the pen is on the glass — close the palm gate.
            markPenDown()
            if (tool == Tool.LASSO) {
                beginRawLasso(touchPoint)
                emitRaw(RawAction.DOWN, RawTool.STYLUS, touchPoint)
                return
            }
            if (tool == Tool.ERASER) {
                beginEraseSweep()
            } else if (isSetup) {
                touchHelper.setRawDrawingRenderEnabled(true)
            }
            emitRaw(RawAction.DOWN, RawTool.STYLUS, touchPoint)
        }

        override fun onEndRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            markPenUp()
            emitRaw(RawAction.UP, RawTool.STYLUS, touchPoint)
            if (tool == Tool.LASSO) {
                // Selection gestures are chrome, not writing — no onPenLifted.
                endRawLasso(touchPoint)
                return
            }
            if (tool == Tool.ERASER) {
                // Flush throttled removals, then commit pixels to the panel once.
                finalizeEraseRedraw()
                post {
                    EpdController.handwritingRepaint(this@OnyxPaperView, Rect(0, 0, width, height))
                }
            }
            firePenLifted()
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
            emitRaw(RawAction.MOVE, RawTool.STYLUS, touchPoint)
            if (tool == Tool.LASSO) {
                if (rawDragActive) {
                    lassoDragMove(touchPoint.x, touchPoint.y)
                } else if (rawLassoCapture) {
                    enableRawLassoRenderOnFirstMove()
                    rawLassoMovePoints.add(touchPoint.toStrokePoint())
                }
                return
            }
            if (tool == Tool.ERASER) {
                eraseAlong(listOf(touchPoint.toStrokePoint()))
            }
        }

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            val points = pointList.points?.map { it.toStrokePoint() } ?: return
            if (points.isEmpty()) return
            if (tool == Tool.LASSO) {
                if (rawLassoCapture && !rawDragActive) rawLassoListPoints.addAll(points)
                return
            }
            if (tool == Tool.ERASER) {
                eraseAlong(points)
            } else {
                // Bake the batch into the committed node so the Android canvas stays
                // current with the overlay's live ink. The SDK may deliver several
                // batches per contact; each becomes its own stroke (matching the
                // reference engine — onPenLifted still fires once, at the end callback).
                commitCapturedStroke(points)
            }
        }

        // Barrel button / stylus eraser end — the SDK intercepts at hardware level and
        // routes here whenever the raw path is enabled, regardless of the armed tool.

        override fun onBeginRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) {
            markPenDown()
            // Mid-lasso barrel press: drop the capture/drag so the erase contact is not
            // also interpreted as a lasso gesture; the SDK's hardware erase proceeds.
            cancelRawLasso("onBeginRawErasing")
            beginEraseSweep()
            if (isSetup) {
                // Release the overlay render first, or erased strokes stay visible.
                touchHelper.setRawDrawingRenderEnabled(false)
                invalidate()
            }
            emitRaw(RawAction.DOWN, RawTool.STYLUS_ERASER, touchPoint)
        }

        override fun onEndRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) {
            markPenUp()
            emitRaw(RawAction.UP, RawTool.STYLUS_ERASER, touchPoint)
            finalizeEraseRedraw()
            post {
                EpdController.handwritingRepaint(this@OnyxPaperView, Rect(0, 0, width, height))
            }
            firePenLifted()
        }

        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {
            emitRaw(RawAction.MOVE, RawTool.STYLUS_ERASER, touchPoint)
            eraseAlong(listOf(touchPoint.toStrokePoint()))
        }

        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) {
            val points = pointList.points?.map { it.toStrokePoint() } ?: return
            if (points.isNotEmpty()) eraseAlong(points)
        }

        /**
         * The SDK's host-facing proximity callback — also enabled by
         * `setPostInputEvent(true)`, repeating at input rate while the pen hovers.
         * Redundant with [penProximitySubscriber] (which carries the exit event too);
         * kept as belt-and-suspenders.
         */
        override fun onPenActive(touchPoint: TouchPoint) {
            markPenInRange()
        }
    }

    private fun TouchPoint.toStrokePoint(): StrokePoint = StrokePoint(
        x = x,
        y = y,
        // Raw digitizer pressure; normalize per device. Tilt stays 0 — the fleet survey
        // found per-device tilt scales with no SDK normalizer (unusable until calibrated).
        pressure = if (maxTouchPressure > 0f) (pressure / maxTouchPressure).coerceIn(0f, 1f) else 1f,
        tilt = 0f,
        timeMillis = timestamp,
    )

    private fun emitRaw(action: RawAction, rawTool: RawTool, tp: TouchPoint) {
        emitRawInput(
            RawInputEvent(
                action = action,
                tool = rawTool,
                x = tp.x,
                y = tp.y,
                pressure = if (maxTouchPressure > 0f) {
                    (tp.pressure / maxTouchPressure).coerceIn(0f, 1f)
                } else 1f,
                tilt = 0f,
                timeMillis = tp.timestamp,
            )
        )
    }

    // ── MotionEvents ─────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Finger input never feeds the SDK pen pipeline — the base decides (selection
        // drag / dismiss-tap while a selection is active) or passes it to the host.
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER
        if (!isStylus) return super.onTouchEvent(event)

        // NONE runs with the raw path off: the stylus arrives here as ordinary events
        // and the base class observes it (gate tracking + raw passthrough). LASSO takes
        // the same route only as the no-pipeline fallback (raw dead → software trail);
        // with the pipeline live the raw callbacks drive the gesture and this path must
        // not double-drive it.
        if (tool == Tool.NONE || (tool == Tool.LASSO && !isSetup)) return super.onTouchEvent(event)

        // PEN/ERASER/LASSO: the SDK owns the stylus. Keep the palm gate correct even if a
        // stylus event slips through (e.g. raw drawing momentarily disabled on focus
        // loss), then feed the SDK. Never fall back to the base class's software ink —
        // a dead pipeline must fail loudly, not silently become the generic engine.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> markPenDown()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> markPenUp()
        }
        return if (isSetup) touchHelper.onTouchEvent(event) else false
    }

    // ── EPD content handoffs ─────────────────────────────────────────────────

    /** True while a posted `handwritingRepaint` is pending, so several content calls in
     *  one main-loop turn (setTemplate + setPageSize + loadStrokes on a page turn)
     *  coalesce into a single full-panel refresh instead of flashing once each. */
    private var epdRepaintPending = false

    /**
     * The overlay handoff every committed-content swap needs on this hardware:
     * render off → repaint the view ([block] must leave the committed layer current) →
     * `handwritingRepaint` (commits the pixels; without it the change is invisible or
     * leaves gray residue) → re-arm the raw path for the current tool.
     */
    private inline fun epdRepaintHandoff(block: () -> Unit) {
        if (!isSetup) {
            block()
            return
        }
        touchHelper.setRawDrawingRenderEnabled(false)
        block()
        if (!epdRepaintPending) {
            epdRepaintPending = true
            post {
                epdRepaintPending = false
                EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                post { armRawForCurrentTool() }
            }
        }
    }

    override fun loadStrokes(strokes: List<Stroke>) = epdRepaintHandoff { super.loadStrokes(strokes) }

    override fun addStrokes(strokes: List<Stroke>) = epdRepaintHandoff { super.addStrokes(strokes) }

    override fun removeStrokes(ids: Collection<String>) = epdRepaintHandoff { super.removeStrokes(ids) }

    override fun clear() = epdRepaintHandoff { super.clear() }

    override fun clearForContentSwap() {
        // Model drops now; pixels stay until the next loadStrokes repaints — but stray
        // pen input during the swap window must not land on the outgoing content.
        if (isSetup) touchHelper.setRawDrawingRenderEnabled(false)
        super.clearForContentSwap()
    }

    override fun setTemplate(bitmap: Bitmap?) = epdRepaintHandoff { super.setTemplate(bitmap) }

    override fun setPageSize(width: Int, height: Int) =
        epdRepaintHandoff { super.setPageSize(width, height) }

    override fun notifyContentChanged() = epdRepaintHandoff { super.notifyContentChanged() }

    override fun releaseRender() {
        if (!isSetup) return
        touchHelper.setRawDrawingRenderEnabled(false)
        invalidate()
        // Re-arms automatically at the next onBeginRawDrawing.
    }

    // ── Exclusion rects ──────────────────────────────────────────────────────

    override fun setExclusionRects(rects: List<Rect>) {
        super.setExclusionRects(rects)
        if (isSetup && penOwner === this) applyLimitRect()
    }

    private fun applyLimitRect() {
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val limitRect = Rect(
            maxOf(0, frame.left - loc[0]),
            maxOf(0, frame.top - loc[1]),
            minOf(width, frame.right - loc[0]),
            minOf(height, frame.bottom - loc[1]),
        )
        // The SDK treats an empty exclusion list as a no-op and keeps (or restores) the
        // previous zone — an off-screen dummy rect forces it to actually clear.
        val exclusion = exclusionRects.takeIf { it.isNotEmpty() }?.map { Rect(it) }
            ?: listOf(Rect(-1, -1, 0, 0))
        touchHelper.setLimitRect(limitRect, exclusion)
    }

    // ── Pipeline lifecycle ───────────────────────────────────────────────────

    private fun openRawDrawing() {
        if (!isSetup) {
            applyLimitRect()
            touchHelper.openRawDrawing()
            isSetup = true
        } else {
            applyLimitRect()
            touchHelper.restartRawDrawing()
        }
        // This view now owns the process-global pipeline; a superseded view's pending
        // close is neutralized by closeRawDrawingIfOwner.
        penOwner = this
        Log.i(TAG, "openRawDrawing: pipeline claimed (${width}x${height})")
        // Master switch for the SDK's PenActiveEvent/PenDeactivateEvent proximity
        // stream (RawInputReader early-returns without it — bytecode-verified, default
        // off). Required for the palm gate's approach window; see penProximitySubscriber.
        touchHelper.setPostInputEvent(true)
        applyToolState()
        EpdController.setUpdListSize(EPD_UPDATE_LIST_SIZE)
    }

    /**
     * Close the global raw-drawing pipeline — only if this view still owns it. When a
     * newer view has claimed it, closing here would tear down THAT view's live session
     * (the "canvas goes dead after switching screens" bug); skip and drop local state.
     * When owner: close and hand the panel back to the device — clear the fast-mode pin
     * and restore the auto-refresh list, so everything else runs under the device's own
     * refresh management.
     */
    private fun closeRawDrawingIfOwner(caller: String) {
        if (!isSetup) return
        // Never carry an in-flight lasso gesture across a pipeline teardown.
        cancelRawLasso(caller)
        if (penOwner === this) {
            touchHelper.closeRawDrawing()
            penOwner = null
            EpdController.clearAppScopeUpdate()
            EpdController.resetUpdListSize()
            Log.i(TAG, "closeRawDrawing: released by owner ($caller)")
        } else {
            Log.i(TAG, "closeRawDrawing: skipped, not owner ($caller)")
        }
        isSetup = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscribePenProximity()
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (width > 0 && height > 0) {
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    openRawDrawing()
                    // The SDK restores its persisted exclusion zone asynchronously during
                    // openRawDrawing, overwriting the setLimitRect issued inside it.
                    // Re-apply next looper turn, after the restore has settled.
                    post { if (isSetup) applyLimitRect() }
                }
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        // The pipeline's limit rect is screen geometry captured at open — a later
        // resize (rotation, insets change) leaves raw input clipped to stale bounds.
        // Post: getLocationOnScreen is unreliable until this layout pass settles.
        if (isSetup && penOwner === this) {
            post { if (isSetup && penOwner === this) applyLimitRect() }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            if (width > 0 && height > 0) {
                openRawDrawing()
                invalidate()
            }
        } else if (isSetup) {
            // A dialog took focus: stop capturing but keep the pipeline (the window is
            // still visible; focus-gain / resumeDrawing re-enables).
            invalidate()
            touchHelper.setRawDrawingEnabled(false)
        }
    }

    /**
     * Hand the panel back to the device the moment this window stops being visible
     * (Home, app switch, opaque activity, screen-off). The app-scope pin and updList
     * override are registered by NAME with the EPD service — without this they keep
     * governing the panel while other apps are on screen (system-wide ghosting).
     * Deliberately NOT on focus loss: a dialog moves focus but keeps the window visible,
     * and the first-stroke pin must survive dialog round-trips. Cross-screen paper→paper
     * navigation is safe via the [penOwner] guard.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) {
            closeRawDrawingIfOwner("onWindowVisibilityChanged")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unsubscribePenProximity()
        closeRawDrawingIfOwner("onDetachedFromWindow")
    }

    override fun resumeDrawing() {
        when {
            // Released (or never opened): reopen once laid out — before layout, the
            // global-layout listener from onAttachedToWindow does it.
            !isSetup -> if (width > 0 && height > 0) openRawDrawing()
            // Another paper surface claimed the pipeline while this one was paused
            // (translucent overlay host). onResume runs before that host's teardown, so
            // reclaim now; its later close is skipped by the ownership guard. This path
            // is deliberately focus-event-independent (window focus is BOOX-flaky).
            penOwner !== this -> openRawDrawing()
            // Still the live owner: just re-enable input, no EPD churn.
            else -> armRawForCurrentTool()
        }
    }

    override fun releaseForHandoff() {
        closeRawDrawingIfOwner("releaseForHandoff")
    }

    override fun release() {
        unsubscribePenProximity()
        closeRawDrawingIfOwner("release")
        super.release()
    }
}
