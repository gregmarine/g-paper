package com.symmetricalpalmtree.gpaper.ratta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.canvas.CanvasPaperView
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle

/**
 * The Supernote (Ratta) engine: live strokes are painted by the firmware's ink daemon
 * on the EPDC overlay ([SupernoteInk] binder client), while point capture, the stroke
 * model, erase hit-testing, template and host-content rendering are all the shared
 * [CanvasPaperView] base — the firmware returns no point data, so everything persisted
 * comes from MotionEvents, exactly like the generic engine.
 *
 * Ported device knowledge (Notesprout `docs/drawing-engine.md`, Ratta section — all
 * hardware-measured on Nomad + Manta):
 *
 * - **Deferred handoff — never bake per pen-lift.** Finished strokes enter the model on
 *   pen-up (saves/hit-tests correct immediately) but the visual bake ([bakeAfterCommit])
 *   waits for a natural boundary; the overlay keeps showing the ink until
 *   [releaseFirmwareOverlay] bakes + clears. Baking per lift fights the hardware —
 *   flash plus ghost/enlargement.
 * - **The three overlay laws:** (1) a `clearAll` reconciles nothing without a
 *   co-presented app frame — always pair with `invalidate()`; (2) clears near pen-lift
 *   and at fresh pen-down can be eaten by the daemon's stroke-finalization window —
 *   remedy is the [releaseGestureTrace] retry ladder plus a flush at the next pen-down;
 *   (3) the firmware latches pen state at contact start — suppress/disable must be
 *   issued from the **hover** stream, before the tip lands ([updateBarrelSuppress]).
 * - **Disable areas are screen-space** and the only "firmware off" switch:
 *   [applyDisableAreas] sends complement bands (everything outside the view's screen
 *   rect) plus the host's exclusion rects; [fullScreenDisable] uses the real panel size
 *   (view dims miss the panel on inset hosts; the display is unreachable after detach).
 * - **Process-global [inkOwner] guard** (mirror of the Onyx `penOwner`): Android runs
 *   the incoming screen's setup before the outgoing screen's teardown, so every
 *   process-global teardown — focus loss, detach, release, and the clear ladder —
 *   checks ownership first.
 * - **Pen-approach re-arm:** an arming issued from attach/focus-gain can land
 *   mid-window-transition and be silently dropped by the daemon (dead session: no live
 *   ink, no bake, strokes still captured). The first stylus approach after setup
 *   re-asserts the whole session from the hover stream ([rearmOnPenApproach]).
 * - **Registration compensation:** the MotionEvent stream lands slightly left of the
 *   physical tip (the firmware ink is true); [compensateRegistration] shifts +2 px
 *   (Nomad) / +3 px (Manta) at every input entry — branched on screen size because the
 *   Manta reports itself as a Nomad in every build property.
 * - **Barrel button / eraser end suppress:** while held / in hover range the firmware
 *   natively paints (or pixel-wipes) ignoring the app's pen config but respecting
 *   disable areas — so both full-screen-disable from hover and let the software erase
 *   do the work.
 *
 * Live ink styling maps [StrokeStyle] to the firmware pen codes measured in the 0…31
 * sweep ([livePenCode]); committed appearance is core-rendered and portable. Firmware
 * failures are loud (`Log.w` via [SupernoteInk]) — no fallback, no engine swap.
 */
internal class RattaPaperView(context: Context) : CanvasPaperView(context) {

    private companion object {
        const val TAG = "GPaperRatta"

        /** Floor/ceiling for the firmware EMR pen size (`px * 100`) — the Needle
         *  penSizeArray runs ~200…2400, and an EMR near 0 paints an invisible
         *  sub-pixel line that reads exactly like a dead firmware path. */
        const val EMR_MIN = 200
        const val EMR_MAX = 1200

        /** Floor for the firmware eraser EMR size (`radius * 50`, min 400 — PoC-validated). */
        const val ERASER_EMR_MIN = 400

        /**
         * Horizontal registration offsets, measured by nudge-to-null on one unit per
         * model: Nomad +2 px, Manta +3 px (≈0.15% of panel width on both). Min screen
         * dimension splits the models (Nomad 1404×1872, Manta 1920×2560) because the
         * Manta's build props are byte-identical to the Nomad's.
         */
        const val REG_OFFSET_NOMAD_PX = 2f
        const val REG_OFFSET_MANTA_PX = 3f
        const val REG_MANTA_MIN_DIM = 1600

        /**
         * Overlay-clear retry ladder (overlay law 2): a clear issued in the wake of a
         * pen-lift lands inside the daemon's stroke-finalization window and is eaten,
         * and the window's length varies by device and moment (450 ms reliable on the
         * Manta but not the Nomad; 2 s always works). Each attempt is an idempotent
         * clearAll + invalidate pair, so retrying costs nothing.
         */
        val GESTURE_TRACE_CLEAR_DELAYS_MS = longArrayOf(450L, 1000L, 1900L)

        /**
         * The view owning the process-global firmware ink state (pen claim, full-UI
         * ink, disable areas, overlay buffer). Activity transitions run the incoming
         * screen's setup BEFORE the outgoing screen's focus-loss/detach teardown, so an
         * unguarded late teardown would clear + disable + un-ink right over the
         * successor's freshly-claimed session. Main-thread only; `@Volatile` defensive.
         */
        @Volatile
        var inkOwner: RattaPaperView? = null
    }

    /** Whether the firmware ink daemon is reachable. Without it the view runs exactly
     *  like the generic engine (live stroke drawn by the base, no binder calls). */
    private val firmware by lazy { SupernoteInk.isAvailable() }

    /**
     * True while finished strokes are shown by the firmware overlay but not yet baked
     * into the committed layer. The strokes themselves are already in the model —
     * purely a "the visual bake is deferred" flag (see [releaseFirmwareOverlay]).
     */
    private var pendingBake = false

    /** Real panel size, cached while attached — the firmware's coordinate space is the
     *  SCREEN, and detach-time teardowns can no longer reach the display. */
    private var screenW = 0
    private var screenH = 0

    override val engineId: String get() = RattaEngine.ENGINE_ID

    override val rendersLiveStrokes: Boolean get() = !firmware

    private fun refreshScreenSize() {
        val d = display ?: return
        val p = Point()
        @Suppress("DEPRECATION") // getRealSize: fine on the two Ratta targets at minSdk 29
        d.getRealSize(p)
        screenW = p.x
        screenH = p.y
    }

    /** Forbid firmware ink everywhere on the PANEL (never just this view's rect). */
    private fun fullScreenDisable() {
        SupernoteInk.setFullScreenDisable(maxOf(screenW, width), maxOf(screenH, height))
    }

    // ── Tool & pen configuration → firmware ──────────────────────────────────

    /** Modes whose visuals are entirely app-drawn — the firmware must paint nothing
     *  anywhere. (Phase 5 moves LASSO out of this set: its live trail becomes the
     *  firmware's own dash pen.) */
    private val firmwareInkSuppressed: Boolean
        get() = tool == Tool.NONE || tool == Tool.LASSO

    override var tool: Tool
        get() = super.tool
        set(value) {
            val changed = super.tool != value
            super.tool = value
            // Every tool change is a handoff boundary: bake + clear FIRST, then push
            // the new tool state.
            if (changed && firmware) firmwareToolBoundary()
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
            if (firmware && inkOwner === this && tool == Tool.ERASER && !barrelDown) {
                SupernoteInk.setEraser(false, eraserEmr())
            }
        }

    /**
     * Live-ink firmware pen code for a [StrokeStyle], from the 0…31 sweep (the mapping
     * table in `StrokeStyle` KDoc / `docs/api.md`). NEEDLE's uniform width matches the
     * committed PEN baseline; the pressure-sensitive INK code carries FOUNTAIN/BRUSH;
     * DASH/CROSS are the firmware's native dash/x streams. CALLIGRAPHY arms 15 (14 is
     * the fallback if 15 disappoints on-device). Code 12 is broken — never armed.
     */
    private fun livePenCode(style: StrokeStyle): Int = when (style) {
        StrokeStyle.PEN, StrokeStyle.MARKER, StrokeStyle.PENCIL -> SupernoteInk.Pen.NEEDLE
        StrokeStyle.FOUNTAIN, StrokeStyle.BRUSH -> SupernoteInk.Pen.INK
        StrokeStyle.DASH -> SupernoteInk.Pen.DASH
        StrokeStyle.CROSS -> SupernoteInk.Pen.CROSS
        StrokeStyle.CALLIGRAPHY -> SupernoteInk.Pen.CALLIGRAPHY
    }

    /** px → firmware EMR size (PoC formula: `width * 100`, clamped visible). */
    private fun emrSize(widthPx: Float): Int = (widthPx * 100f).toInt().coerceIn(EMR_MIN, EMR_MAX)

    /** Eraser EMR size (PoC formula: `radius * 50` with a working floor). */
    private fun eraserEmr(): Int = (eraserRadius * 50f).toInt().coerceAtLeast(ERASER_EMR_MIN)

    /** Arm the firmware pen with the current style/width and the armed colour mapped to
     *  the nearest firmware grey — the baked stroke keeps its true ARGB value. */
    private fun applyPenToFirmware() {
        SupernoteInk.setPen(
            livePenCode(penStyle),
            emrSize(penWidth),
            RattaInkMap.firmwareColorFor(penColor),
        )
    }

    private fun rearmPenIfLive() {
        if (firmware && inkOwner === this && tool == Tool.PEN &&
            !firmwareInkSuppressed && !barrelDown
        ) {
            applyPenToFirmware()
        }
    }

    /**
     * Push the current tool state to the firmware — the per-mode half of every handoff.
     * Callers that change tool MUST release the overlay first ([firmwareToolBoundary]).
     */
    private fun applyToolToFirmware() {
        if (!firmware) return
        barrelDown = false // a tool push supersedes the transient barrel disable
        if (firmwareInkSuppressed) {
            fullScreenDisable()
            return
        }
        applyDisableAreas()
        when (tool) {
            Tool.ERASER ->
                // Round eraser, colour-255 payload: the firmware stops painting ink
                // along the path (and natively wipes its own overlay pixels); the
                // base's software hit-test does the actual stroke removal.
                SupernoteInk.setEraser(false, eraserEmr())
            else -> applyPenToFirmware()
        }
    }

    /** Tool-change boundary: bake + clear the overlay FIRST, then push the new state. */
    private fun firmwareToolBoundary() {
        if (!firmware) return
        releaseFirmwareOverlay()
        applyToolToFirmware()
    }

    /**
     * Clip firmware ink to this view's on-screen rect minus the host's exclusion rects.
     * The firmware paints in SCREEN space wherever the pen lands — it knows nothing of
     * view bounds or the window stack — so the disable set is complement bands (up to
     * four rects covering everything OUTSIDE the view's screen rect; all empty on a
     * full-bleed host) plus the host chrome rects offset into screen coordinates.
     */
    private fun applyDisableAreas() {
        if (!firmware) return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        // Never trust a stale/zero screen size below the view's own extent.
        val sw = maxOf(screenW, loc[0] + width)
        val sh = maxOf(screenH, loc[1] + height)
        val rects = mutableListOf<Rect>()
        if (loc[1] > 0) rects += Rect(0, 0, sw, loc[1]) // above
        if (loc[1] + height < sh) rects += Rect(0, loc[1] + height, sw, sh) // below
        if (loc[0] > 0) rects += Rect(0, loc[1], loc[0], loc[1] + height) // left
        if (loc[0] + width < sw) rects += Rect(loc[0] + width, loc[1], sw, loc[1] + height) // right
        for (r in exclusionRects) rects += Rect(r).apply { offset(loc[0], loc[1]) }
        if (rects.isEmpty()) SupernoteInk.clearDisableAreas()
        else SupernoteInk.setDisableAreas(rects)
    }

    override fun setExclusionRects(rects: List<Rect>) {
        super.setExclusionRects(rects)
        // Apply live unless a full-screen disable owns the areas right now (suppressed
        // mode or held barrel) — leaving those re-applies via applyToolToFirmware.
        if (firmware && inkOwner === this && !firmwareInkSuppressed && !barrelDown) {
            applyDisableAreas()
        }
    }

    // ── Deferred bake & overlay handoff ──────────────────────────────────────

    override fun bakeAfterCommit() {
        if (firmware) {
            // Deferred handoff: the stroke is in the model NOW; the overlay keeps
            // showing the ink until a natural boundary bakes + clears.
            pendingBake = true
        } else {
            super.bakeAfterCommit()
        }
    }

    override fun redrawCommitted() {
        if (!firmware) {
            super.redrawCommitted()
            return
        }
        // Every re-record bakes the whole model — pending overlay-shown strokes
        // included. If the overlay was still showing them, hand off now so the same ink
        // is never displayed by both layers. This guard keeps any redraw triggered
        // outside releaseFirmwareOverlay (throttled erase redraws, host content
        // changes) correct.
        //
        // Ordering is the reference engine's, exactly: record → clearAll → invalidate.
        // The daemon pairs a clear with the NEXT app frame it sees (law 1), so the
        // clear must be issued before the fresh frame — a clear issued after an
        // invalidate can pair with a stale in-flight frame recorded before the bake
        // (hosts may present frames at input rate), reconciling the overlay drop
        // against stroke-less pixels: the just-written ink visibly vanishes until a
        // later repaint damages the region.
        if (!recordCommitted()) return
        val baked = pendingBake
        if (baked) {
            pendingBake = false
            SupernoteInk.clearAll()
        }
        invalidate()
        // Belt-and-suspenders for the pairing race above and for eaten clears (law 2):
        // after a bake every possible frame already contains the strokes, so the
        // ladder's retry pairs are harmless when the handoff landed (clear of an empty
        // buffer + frame with no damage) and heal the panel within ~450 ms when it
        // didn't. The reference never needed this only because its hosts present no
        // frames mid-writing.
        if (baked) armOverlayClearLadder()
    }

    /**
     * The handoff: bake any overlay-shown strokes into the committed layer, then clear
     * the firmware overlay so the app layer takes over. Natural boundaries ONLY — never
     * per pen lift.
     */
    private fun releaseFirmwareOverlay() {
        if (!firmware) return
        if (pendingBake) {
            redrawCommitted() // its guard clears the overlay with a co-presented frame
        } else {
            // Nothing to bake — wipe any overlay residue. No invalidate: unchanged
            // content would pay a pointless EPD refresh; callers that change content
            // repaint themselves, and the ladder pairs its own clears with frames.
            SupernoteInk.clearAll()
        }
    }

    // ── Gesture-trace clear ladder (overlay law 2) ───────────────────────────

    private var overlayClearArmed = false
    private var overlayClearAttempt = 0

    /**
     * Release the overlay after an erase contact (and, in Phase 5, gesture-consumed
     * lasso trails): overlay ink that corresponds to nothing in the app layer. The
     * immediate release is correct for the model, but its clear fires inside the
     * daemon's stroke-finalization window and is usually eaten — the ladder's timed
     * clearAll + invalidate pairs are what actually wipe the trace.
     */
    private fun releaseGestureTrace() {
        if (!firmware) return
        releaseFirmwareOverlay()
        armOverlayClearLadder()
    }

    /** (Re-)start the timed clearAll + invalidate retries. Idempotent; each firing is
     *  invisible once the overlay and panel agree. */
    private fun armOverlayClearLadder() {
        overlayClearArmed = true
        overlayClearAttempt = 0
        removeCallbacks(overlayClearRunnable)
        postDelayed(overlayClearRunnable, GESTURE_TRACE_CLEAR_DELAYS_MS[0])
    }

    private val overlayClearRunnable = object : Runnable {
        override fun run() {
            if (!firmware || !overlayClearArmed) return
            // Ownership moved to another paper screen mid-ladder: a clearAll now would
            // wipe THEIR live overlay ink. Stand down.
            if (inkOwner !== this@RattaPaperView) {
                overlayClearArmed = false
                return
            }
            // Never wipe live ink: mid-stroke → retry shortly; strokes pending bake →
            // the overlay is showing needed ink, leave it for the next boundary.
            if (isPenDown) {
                postDelayed(this, GESTURE_TRACE_CLEAR_DELAYS_MS[0])
                return
            }
            if (pendingBake) {
                overlayClearArmed = false
                return
            }
            SupernoteInk.clearAll()
            invalidate() // law 1: the wipe only reaches the panel with an app frame
            overlayClearAttempt++
            if (overlayClearAttempt < GESTURE_TRACE_CLEAR_DELAYS_MS.size) {
                postDelayed(
                    this,
                    GESTURE_TRACE_CLEAR_DELAYS_MS[overlayClearAttempt] -
                        GESTURE_TRACE_CLEAR_DELAYS_MS[overlayClearAttempt - 1],
                )
            } else {
                overlayClearArmed = false
            }
        }
    }

    /**
     * Extra armed-clear attempt at fresh pen contact — the one moment measured working
     * in every round. Does NOT disarm the ladder: if this attempt is eaten too, the
     * timed retries still run (they self-disarm via [pendingBake] once new ink commits).
     */
    private fun flushArmedOverlayClear() {
        if (!pendingBake) {
            SupernoteInk.clearAll()
            invalidate()
        }
    }

    // ── Barrel button / eraser end (hover suppress — overlay law 3) ──────────

    private var barrelDown = false

    /**
     * While the side button is held (or the physical eraser end is in EMR range) the
     * firmware natively paints its x-stream trace / pixel-wipes the panel, ignoring the
     * app's pen config but respecting disable areas — so full-screen-disable from the
     * hover stream (the only moment that beats the firmware's contact-start latch;
     * an ACTION_DOWN disable is the no-hover backstop) and let the base's software
     * erase do the work. Release → re-apply the armed tool.
     */
    private fun updateBarrelSuppress(event: MotionEvent) {
        if (!firmware) return
        val pressed = when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_ERASER -> true
            MotionEvent.TOOL_TYPE_STYLUS ->
                (event.buttonState and
                    (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
            else -> false
        }
        if (firmwareInkSuppressed) return // those modes already own a full-screen disable
        if (pressed == barrelDown) return
        barrelDown = pressed
        if (pressed) fullScreenDisable()
        else applyToolToFirmware()
    }

    // ── Pen-approach re-arm ──────────────────────────────────────────────────

    /**
     * One-shot re-assert of the whole firmware session on the first stylus approach
     * after [setupFirmwareInk] — an arming issued from attach/focus-gain can land
     * mid-window-transition and be silently dropped by the daemon (measured: a
     * byte-identical correct arming sequence still produced a dead session). By the
     * time the pen approaches, this window is definitively front. Direct calls rather
     * than [setupFirmwareInk] so the firing doesn't re-arm its own one-shot. Runs
     * BEFORE [updateBarrelSuppress] on the same event, whose re-evaluation immediately
     * re-applies any transient full-screen disable this re-arm just cleared.
     */
    private var penApproachRearmPending = false

    private fun rearmOnPenApproach() {
        if (!firmware || !penApproachRearmPending || inkOwner !== this) return
        penApproachRearmPending = false
        SupernoteInk.claimPen()
        SupernoteInk.enableFullUiAuto(context, true)
        applyToolToFirmware()
    }

    // ── Registration compensation ────────────────────────────────────────────

    private val regOffsetXPx: Float by lazy {
        val dm = resources.displayMetrics
        if (minOf(dm.widthPixels, dm.heightPixels) >= REG_MANTA_MIN_DIM) REG_OFFSET_MANTA_PX
        else REG_OFFSET_NOMAD_PX
    }

    /**
     * The digitizer's MotionEvent x lands a few px LEFT of the physical tip while the
     * firmware's live ink is true — shift every stylus/eraser event right by the
     * measured constant (historical samples included) at every input entry, before any
     * consumer, so persisted data matches physical truth and the bake lands exactly
     * under the live ink. Finger events are untouched (the offset is a property of the
     * EMR digitizer, not the touch panel).
     */
    private fun compensateRegistration(event: MotionEvent) {
        val t = event.getToolType(0)
        if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
            event.offsetLocation(regOffsetXPx, 0f)
        }
    }

    // ── Input ────────────────────────────────────────────────────────────────

    /** Whether the current contact is erasing — latched at ACTION_DOWN, mirroring the
     *  base's gesture latch, so the erase boundaries fire exactly once per contact. */
    private var contactErasing = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Correct the digitizer offset before ANY consumer — writing, erasing and
        // hit-tests must all agree on where the pen physically is.
        compensateRegistration(event)
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER
        if (isStylus && firmware) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // No-hover backstop for the pen-approach re-arm (too late for this
                    // stroke's paint, but heals the session for the rest).
                    rearmOnPenApproach()
                    // Fresh EMR contact: fire any armed gesture-trace clear before this
                    // contact produces new overlay ink.
                    if (overlayClearArmed) flushArmedOverlayClear()
                    // Mirror of the base's gesture classification. An erase contact is
                    // a handoff boundary: bake pending overlay ink FIRST so the
                    // software erase + redraw operates on a fully-baked page (the
                    // firmware natively wipes only its own overlay pixels).
                    contactErasing = !firmwareInkSuppressed && (
                        toolType == MotionEvent.TOOL_TYPE_ERASER ||
                            tool == Tool.ERASER ||
                            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                        )
                    if (contactErasing) releaseFirmwareOverlay()
                }
                else -> Unit
            }
            // Contact-time button changes (pressed at pen-down, released at lift) —
            // hover tracking alone would miss them.
            updateBarrelSuppress(event)
        }
        val handled = super.onTouchEvent(event)
        if (isStylus && firmware &&
            (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            if (contactErasing) {
                // Every erase contact ends with the clear ladder: the pen-down
                // bake+clear can be eaten (an erased stroke's overlay twin stays frozen
                // on the panel, hiding the repaint), and a contact that armed the
                // eraser mid-approach leaves a partial pen trace. Idempotent when clean.
                releaseGestureTrace()
            }
            contactErasing = false
        }
        return handled
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        compensateRegistration(event)
        rearmOnPenApproach()
        updateBarrelSuppress(event)
        return super.onHoverEvent(event)
    }

    // Some stacks report button changes as ACTION_BUTTON_PRESS/RELEASE generic events
    // rather than a buttonState change on the hover stream — catch those too.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        compensateRegistration(event)
        rearmOnPenApproach()
        updateBarrelSuppress(event)
        return super.onGenericMotionEvent(event)
    }

    // ── Content boundaries ───────────────────────────────────────────────────

    override fun loadStrokes(strokes: List<Stroke>) {
        // Boundary: bake + clear before the content swap so no old overlay ink survives.
        releaseFirmwareOverlay()
        super.loadStrokes(strokes)
    }

    override fun clear() {
        // The model empties and redrawCommitted's guard performs the overlay handoff in
        // the correct order (clear before the empty frame presents) if strokes were
        // still overlay-shown; the trailing clearAll wipes any other overlay residue.
        super.clear()
        if (firmware) SupernoteInk.clearAll()
    }

    override fun clearForContentSwap() {
        // Bake + release FIRST (or the outgoing page's live overlay ink survives onto
        // the incoming page); the pixels then stay until the next loadStrokes repaints.
        releaseFirmwareOverlay()
        super.clearForContentSwap()
    }

    override fun setTemplate(bitmap: Bitmap?) {
        releaseFirmwareOverlay()
        super.setTemplate(bitmap)
    }

    // addStrokes / removeStrokes / setPageSize / notifyContentChanged need no override:
    // they re-record through redrawCommitted, whose pendingBake guard performs the
    // overlay handoff whenever the overlay was still showing unbaked ink.

    override fun releaseRender() {
        // Host chrome touch — the Ratta analogue of releasing the EPD overlay: bake
        // pending ink and clear so chrome paints clean.
        if (firmware) releaseFirmwareOverlay()
    }

    // ── Lifecycle — process-global firmware session ──────────────────────────

    /** (Re-)claim the firmware pen and turn on full-UI ink. Idempotent, safe to call often. */
    private fun setupFirmwareInk() {
        if (!firmware) return
        inkOwner = this // process-global claim — a predecessor's late teardown now skips
        penApproachRearmPending = true
        SupernoteInk.claimPen()
        SupernoteInk.enableFullUiAuto(context, true)
        SupernoteInk.enableAutoRegal(context, true) // anti-ghosting; keeps handoffs clean
        applyToolToFirmware()
        Log.i(TAG, "firmware ink session claimed (${width}x$height)")
    }

    private fun teardownFirmwareInk() {
        if (!firmware) return
        // A successor already set up (translucent overlay host): the firmware is theirs
        // now — touching it would kill their live ink.
        if (inkOwner !== this) return
        releaseFirmwareOverlay()
        // There is no unclaim transaction; the enforceable equivalent is a full-screen
        // disable — while we are unfocused the firmware must not paint on our behalf.
        fullScreenDisable()
        SupernoteInk.enableFullUiAuto(context, false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshScreenSize()
        // Before first layout width/height are 0 — a full-screen disable would be an
        // empty rect and getLocationOnScreen garbage. onSizeChanged runs the setup.
        if (width > 0 && height > 0) setupFirmwareInk()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        // First layout after attach (the deferred setup) and any later resize (the
        // disable-area screen offsets shift with layout). Idempotent.
        if (isAttachedToWindow) {
            refreshScreenSize()
            setupFirmwareInk()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // The view stays attached across a task switch, so attach won't re-run. While
        // we're away the firmware hands the pen to other apps and resets full-UI ink —
        // a focus gain must re-assert the WHOLE setup, not just re-enable.
        if (hasWindowFocus) {
            if (width > 0 && height > 0) setupFirmwareInk()
        } else {
            teardownFirmwareInk()
        }
    }

    override fun onDetachedFromWindow() {
        overlayClearArmed = false
        removeCallbacks(overlayClearRunnable)
        if (firmware && inkOwner === this) {
            releaseFirmwareOverlay()
            // Full-screen disable, not clearDisableAreas: between this view's death and
            // the next surface's setup nothing may let the firmware paint stray ink.
            // Device-confirmed harmless system-wide (the daemon resets per-claim state).
            fullScreenDisable()
            SupernoteInk.enableFullUiAuto(context, false)
            inkOwner = null // also drops the static view ref — no Activity leak
            Log.i(TAG, "firmware ink session released (detach)")
        }
        super.onDetachedFromWindow()
    }

    override fun resumeDrawing() {
        // Host onResume — the focus-independent reclaim (focus events are unreliable on
        // e-ink), and what flips inkOwner back after a translucent overlay host: our
        // onResume runs before its teardown, which the ownership guard then skips.
        if (width > 0 && height > 0) setupFirmwareInk()
        // else not laid out yet — onSizeChanged runs the setup after first layout.
    }

    override fun releaseForHandoff() {
        if (!firmware || inkOwner !== this) return
        releaseFirmwareOverlay()
        SupernoteInk.enableFullUiAuto(context, false)
        // inkOwner stays ours: if the successor never claims (edge), our detach cleans
        // up; when it claims, setupFirmwareInk overwrites the token and our teardowns skip.
        Log.i(TAG, "firmware ink released for handoff")
    }

    override fun release() {
        overlayClearArmed = false
        removeCallbacks(overlayClearRunnable)
        if (firmware && inkOwner === this) {
            releaseFirmwareOverlay()
            fullScreenDisable()
            SupernoteInk.enableFullUiAuto(context, false)
            inkOwner = null
            Log.i(TAG, "firmware ink session released (release)")
        }
        super.release()
    }
}
