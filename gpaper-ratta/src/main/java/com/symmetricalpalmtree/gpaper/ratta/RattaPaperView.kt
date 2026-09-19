package com.symmetricalpalmtree.gpaper.ratta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import com.symmetricalpalmtree.gpaper.core.PageMode
import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.canvas.CanvasPaperView
import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

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

        /** Floor for the firmware eraser EMR size (`radius * 50`, min 400 — PoC-validated). */
        const val ERASER_EMR_MIN = 400

        /** EMR size for the firmware lasso trail (the DASH pen) — the exact size the
         *  0…31 sweep measured that code rendering at. Independent of the ink pen's
         *  width mapping: the trail is chrome, not ink. */
        const val LASSO_TRAIL_EMR = 300

        /**
         * Horizontal registration offsets, measured by nudge-to-null on one unit per
         * model: Nomad +2 px, Manta +3 px (≈0.15% of panel width on both). Min screen
         * dimension splits the models (Nomad 1404×1872, Manta 1920×2560) because the
         * Manta's build props are byte-identical to the Nomad's.
         */
        const val REG_OFFSET_NOMAD_PX = 2f
        const val REG_OFFSET_MANTA_PX = 3f
        const val REG_MANTA_MIN_DIM = 1600

        // ── The raster page's measured numbers (0.1.32, frozen 0.1.34) ──────────
        //
        // Each was settled by hand on a Supernote Nomad on 2026-09-15 and carried for
        // one arc behind a `setprop` door (`RattaTuning`) so arc 43's later walks could
        // re-open a question without a rebuild per candidate. None was re-opened, so
        // the door closed at 0.1.34 and the measurements are constants. Re-opening one
        // means another walk, not another knob: a judgement of *feel* is worth only what
        // the hand that made it was comparing against. (The fourth of that walk's
        // numbers, the `PENCIL` EMR floor, is `RattaEmr.EMR_MIN_HAIRLINE`; another,
        // the pencil's single preview grey, became a rung of
        // [RattaInkMap.pencilPreviewFor]'s ladder at 0.1.36 when the pencil grew
        // fifteen shades — the same answer for the lead it was measured on.)

        /**
         * How often the raster eraser redraws mid-sweep here, in ms —
         * **measured 16 ms, one frame**, which is the number Onyx uses and is arrived
         * at for a different reason. That is exactly why the core seam
         * (`rasterEraseRedrawIntervalMs`) stays a seam: the two engines agree on the
         * value, not on why, and the next panel will have its own answer.
         *
         * 100 ms was good (113 frames / 20 % janky over a minute), 60 ms better
         * (162 / 22 %), 16 ms the artist's clear choice at 756 / 82 % — *"this eraser
         * works better on Ratta hardware than it does on Onyx"*. The frame count is six
         * times worse and the hand is right, because the frame-silence rule's cost is
         * the *masking* an overlay imposes on frames presented under it and an erase
         * contact releases the overlay at ACTION_DOWN: nothing accumulates, and what is
         * left is the panel's own update, which the Nomad keeps up with. 250 ms and
         * end-only were never walked — there was no reason to go slower once 16 won.
         */
        const val RASTER_ERASE_REDRAW_MS = 16L

        /**
         * The constant pressure `PENCIL` **bakes** at on a raster page when the firmware
         * needle is what previews it — **measured 0.5**, against the DARK_GRAY preview a
         * `#505050` lead was armed with (that single preview grey is now a rung of
         * [RattaInkMap.pencilPreviewFor]'s ladder, which answers DARK_GRAY for the same
         * lead; the pairing the walk settled is unchanged).
         *
         * **Since 0.1.41 that is the fallback path rather than the usual one.** Everything
         * below is still true of the firmware daemon and none of it has been re-opened —
         * but the direct panel preview ([EbcPanel]) never goes through the daemon, and it
         * paints sixteen greys, so on that path there is nothing to give up and the pencil
         * bakes at the pressure the hand applied (see [bakePressure]). This constant is
         * read only when the panel is unavailable and the needle is back.
         *
         * **Why the bake gives way and not the preview.** Three rounds on the Nomad
         * tried to make a pressure-toned bake agree with its preview from the preview's
         * side and could not: the greys are fixed per arming, and the pressure-sensitive
         * pen codes vary *width*, not tone. There is nothing on the preview's side left
         * to vary, so on this engine the pencil gives up its tonal range instead — live
         * ink and baked ink agree, and the mark on the panel is the mark that was drawn.
         * The bake was never wrong; it was right about a tone the panel could not show
         * while the pen was down, which is a different fault and takes a different fix.
         *
         * **Ratta only.** BOOX and Paintsprout keep the pressure pencil — their preview
         * can carry tone, so they have nothing to give up — and stroke mode is untouched
         * on every engine: the pressures a host persists are the ones that were measured.
         */
        const val PENCIL_BAKE_PRESSURE = 0.5f

        /** Paper, as the flatten starts from it: `0xRRGGBB`, no alpha. */
        const val WHITE_RGB = 0xFFFFFF

        /** The alpha the flattened pixel is handed to the tone table with — it is a colour
         *  on paper by then, never a translucent one. */
        const val OPAQUE = 0xFF000000.toInt()

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
     * The panel driver, opened directly (Phase 28, 0.1.41) — what the pencil previews
     * through when it can. Opened from [setupFirmwareInk], closed at detach/release, and
     * inert when the driver refused: see [EbcPanel] and [directPencil].
     */
    private val panel = EbcPanel()

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

    /** The lasso trail is the firmware's dash pen; the base must not double-draw it. */
    override val rendersLiveTrail: Boolean get() = !firmware

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
     *  anywhere: the NONE tool, and the whole of a selection drag-move contact (the
     *  drag layer is app-drawn; a firmware dash trail under it would be stray ink). */
    private val firmwareInkSuppressed: Boolean
        get() = tool == Tool.NONE || isSelectionDragActive

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

    override var pageMode: PageMode
        get() = super.pageMode
        set(value) {
            val changed = super.pageMode != value
            super.pageMode = value
            // The page's mode is one of [directPencil]'s preconditions — a host switching a
            // pencil page between raster and stroke changes which thing draws the live ink,
            // and the base's setter (a content swap) knows nothing of the firmware.
            if (changed && firmware) rearmPenIfLive()
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
     *
     * `PENCIL` stays on NEEDLE: arming the pressure-sensitive INK for it was tried on the
     * Nomad (2026-09-15) and made no visible difference, while the pressure codes vary
     * *width* — the one thing a preview must never lie about. The pencil's tone problem
     * was answered at the bake instead ([PENCIL_BAKE_PRESSURE]).
     */
    private fun livePenCode(style: StrokeStyle): Int = when (style) {
        StrokeStyle.PEN, StrokeStyle.MARKER, StrokeStyle.PENCIL -> SupernoteInk.Pen.NEEDLE
        StrokeStyle.FOUNTAIN, StrokeStyle.BRUSH -> SupernoteInk.Pen.INK
        StrokeStyle.DASH -> SupernoteInk.Pen.DASH
        StrokeStyle.CROSS -> SupernoteInk.Pen.CROSS
        StrokeStyle.CALLIGRAPHY -> SupernoteInk.Pen.CALLIGRAPHY
    }

    /** Eraser EMR size (PoC formula: `radius * 50` with a working floor). */
    private fun eraserEmr(): Int = (eraserRadius * 50f).toInt().coerceAtLeast(ERASER_EMR_MIN)

    /**
     * The firmware colour for the armed pen: the nearest firmware grey to the ink's own
     * colour, so the pen-lift handoff is invisible — except for `PENCIL`, which reads
     * its own ladder ([RattaInkMap.pencilPreviewFor]). Graphite bakes as a scatter of
     * flecks with bare paper between them, here at a constant pressure and upright, and
     * reads far paler than the solid line any firmware code paints, so the tone that
     * matches pen-up is offset pale-ward of the nearest grey to the lead's colour
     * (`#505050`'s nearest is BLACK) and stops short of the near-invisible LIGHT_GRAY.
     * Both are still *mappings from the armed colour*: since 0.1.36 a black lead and a
     * pale one preview differently, because the pencil has fifteen shades to tell apart.
     * The baked stroke keeps its true ARGB value in either case.
     *
     * Re-read at every arming, and [penColor]'s setter re-arms — so picking a shade
     * mid-page changes the live tone without a tool boundary.
     */
    private fun firmwarePenColor(): Int =
        if (penStyle == StrokeStyle.PENCIL) RattaInkMap.pencilPreviewFor(penColor)
        else RattaInkMap.firmwareColorFor(penColor)

    /** Arm the firmware pen with the current style/width and its live colour. */
    private fun applyPenToFirmware() {
        SupernoteInk.setPen(
            livePenCode(penStyle),
            RattaEmr.penSize(penStyle, penWidth),
            firmwarePenColor(),
        )
    }

    private fun rearmPenIfLive() {
        if (firmware && inkOwner === this && tool == Tool.PEN &&
            !firmwareInkSuppressed && !barrelDown
        ) {
            // The direct pencil wants the daemon OFF, and this is the path every
            // style/colour/width/page-mode change comes down — so pencil → pen re-arms the
            // needle and pen → pencil takes it away again, with no tool boundary needed.
            if (directPencil) fullScreenDisable() else applyPenToFirmware()
        }
    }

    /**
     * Whether the mark now under the hand previews **through the panel directly** rather
     * than through the firmware's needle (Phase 28).
     *
     * Every clause is a precondition of the honesty of that preview, not a policy:
     * the panel must have opened; the page must be a raster one, because the preview is
     * flattened against the page images and there is nothing to flatten against in stroke
     * mode; the style must be `PENCIL`, because what is painted is [GraphiteGrain]'s flecks
     * and nothing else has a prefix-stable texture to lay one fleck at a time; and the tool
     * must be the pen, because the rubber and the lasso have their own live chrome that the
     * daemon still draws. Anything else keeps the firmware path exactly as 0.1.40 had it.
     */
    private val directPencil: Boolean
        get() = firmware && panel.isOpen && pageMode == PageMode.RASTER &&
            penStyle == StrokeStyle.PENCIL && tool == Tool.PEN

    /**
     * Push the current tool state to the firmware — the per-mode half of every handoff.
     * Callers that change tool MUST release the overlay first ([firmwareToolBoundary]).
     */
    private fun applyToolToFirmware() {
        if (!firmware) return
        barrelDown = false // a tool push supersedes the transient barrel disable
        lassoHoverSuppressed = false // ditto the lasso drag hover suppress
        if (firmwareInkSuppressed) {
            fullScreenDisable()
            return
        }
        if (directPencil) {
            // Atelier's arrangement: with the app painting the panel itself, the daemon must
            // not paint over it. A full-screen disable is the only "firmware off" switch
            // there is (the disable areas are screen-space), and it is issued from the same
            // tool push the needle would have been armed from, so the hand-over happens
            // wherever a tool, style, colour, width or page mode changes.
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
            Tool.LASSO ->
                // Live lasso trail = the firmware's own dash stream, black, chrome size.
                SupernoteInk.setPen(SupernoteInk.Pen.DASH, LASSO_TRAIL_EMR, SupernoteInk.Color.BLACK)
            Tool.LASSO_ERASER ->
                // The lasso eraser's trail = the firmware's x-stream (the Supernote
                // lasso-eraser look), same chrome size (0.1.28).
                SupernoteInk.setPen(SupernoteInk.Pen.CROSS, LASSO_TRAIL_EMR, SupernoteInk.Color.BLACK)
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
            // The direct pencil owns a full-screen disable; re-sending the complement bands
            // here would hand the daemon back the paper it is meant to be kept off.
            if (directPencil) fullScreenDisable() else applyDisableAreas()
        }
    }

    // ── Deferred bake & overlay handoff ──────────────────────────────────────

    override fun bakeAfterCommit() {
        if (firmware && contactDirect) {
            // The direct pencil (Phase 28) is the one case with nothing to defer: the daemon
            // was disabled for this contact, so there is no overlay copy of the ink to keep
            // showing and none to drop. Record and present at once — the window's own pixels
            // become the mirror of what the panel is already displaying, level for level
            // (RattaPanelTone), and the panel does not move. Deferring instead would leave
            // the mark live on the panel and absent from every frame until some later
            // boundary, which is a mark that vanishes if anything at all repaints.
            super.bakeAfterCommit()
            clearLivePreview()
            return
        }
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

    // ── The raster page (0.1.32; the numbers frozen at 0.1.34) ──────────────

    /**
     * The raster eraser's redraw cadence on Supernote — [RASTER_ERASE_REDRAW_MS], whose
     * KDoc holds the measurement. Onyx can afford a frame's cadence because its engine
     * answers `presentRasterEraseProgress` with a regional `handwritingRepaint` of
     * exactly the rubbed corridor; no such transaction exists here, and the same number
     * is reached from the other direction.
     */
    override val rasterEraseRedrawIntervalMs: Long get() = RASTER_ERASE_REDRAW_MS

    /**
     * `PENCIL` bakes at a constant pressure on this engine ([PENCIL_BAKE_PRESSURE], whose
     * KDoc holds the reasoning and the measurement) **while the firmware needle is what
     * previews it**, so the live ink and the baked ink agree.
     *
     * Two things turn it back off, and both are the same rule seen from different sides:
     * *the preview and the bake must agree, so the bake gives up only what the preview
     * cannot show.* With the binder absent this view draws its own live ink through the
     * renderer the bake uses, which carries pressure. And since 0.1.41, with the panel open
     * the preview is [GraphiteGrain]'s own flecks painted straight into sixteen greys — it
     * shows pressure exactly, because it is the same grain — so the pencil takes its tonal
     * range back. Nothing about the 0.5 measurement was wrong; the thing it was compensating
     * for is gone.
     */
    override fun bakePressure(style: StrokeStyle, pressure: Float): Float =
        if (style == StrokeStyle.PENCIL && firmware && !panel.isOpen) PENCIL_BAKE_PRESSURE
        else pressure

    /**
     * `PENCIL` bakes upright while the needle previews it: the firmware's live line cannot
     * widen with lean, so a bake that did would be up to ~11× the line that was previewed
     * (0.1.35, found on the Manta). Unlike [bakePressure] this does NOT relax on the direct
     * panel path: the preview could show a leaned lead now, but the user's decision
     * (2026-09-18, the Manta walk — "too wide for a 1.2 px lead") keeps the Supernote
     * pencil upright. Width comes from the lead size alone.
     */
    override fun bakeTilt(style: StrokeStyle, tilt: Float): Float =
        if (style == StrokeStyle.PENCIL && firmware) 0f else tilt

    /**
     * On the direct panel path the pencil's flecks are opaque, for the bake and the live
     * preview alike (Phase 28, the user's Nomad walk of 2026-09-18): the panel's 16-grey
     * waveform lands a black pixel on its first frame but reaches a grey only by passing
     * through black, so alpha-graded flecks trailed the nib and read as a solid line, while
     * black ones landed under it — the probe's finding, repeated inside the engine. Tone is
     * density and fleck size, which is what pressure already drives in `GraphiteGrain`.
     * The needle fallback keeps the alpha-graded bake it was measured with.
     */
    override val opaquePencilFlecks: Boolean get() = firmware && panel.isOpen

    // ── The direct pencil: graphite painted onto the panel (Phase 28, 0.1.41) ──
    //
    // What the firmware needle could never do: sixteen greys, pressure, tilt, and no change
    // at pen-up. The mark is [GraphiteGrain]'s flecks — the *same* flecks the bake will lay,
    // from the same call with the same seed — rasterised by the same renderer into a live
    // alpha layer, flattened against the page images exactly as `drawCommittedContent`
    // flattens them, toned through [RattaPanelTone] and written straight into the panel
    // driver's frame 0. At pen-up the stroke bakes into the page image as it always did and
    // the window presents it: the compositor rewrites the same pixels, the tone table says
    // they map to the levels already showing, and the panel does not move. That is the
    // mirror, and it is why the tone table has to be the measured one.
    //
    // Three rules hold this together, each of them a measurement:
    //
    //  1. **The window is never invalidated mid-stroke on this path.** The compositor's
    //     rewrite of frame 0 is *its* copy of the window, which knows nothing of the dabs
    //     just painted; a frame presented mid-stroke replaces fresh graphite with older
    //     pixels. The base already draws no live ink here (`rendersLiveStrokes = !firmware`)
    //     and nothing below calls invalidate — deliberately, and it is the first thing to
    //     check if live ink starts blinking.
    //  2. **The daemon is disabled while the pencil is armed** ([applyToolToFirmware]), or
    //     its needle paints over the grain in one flat grey.
    //  3. **Every live pixel goes through [RattaPanelTone]**, so the recompose is a no-op.

    /**
     * The alpha of every fleck laid this contact — one byte a pixel, the view's size,
     * allocated on the first direct contact and reused for the life of the view.
     *
     * A byte array rather than a page-sized ARGB bitmap because only alpha varies: the lead
     * has one colour and [GraphiteGrain] gives each fleck a darkness, which
     * `StrokeRenderer` renders as that colour at an alpha. Keeping the colour out of it
     * means the live layer costs a quarter of a bitmap, and the flatten below applies
     * [penColor] once per pixel instead of storing it a million times.
     */
    private var liveAlpha: ByteArray? = null
    private var liveAlphaW = 0
    private var liveAlphaH = 0

    /** Scratch the new flecks of one batch are rasterised into, grown as batches demand and
     *  never shrunk — a fast hand covers more paper per sample than a slow one. */
    private var batchBitmap: Bitmap? = null
    private var batchCanvas: Canvas? = null

    /**
     * The stroke under the pen, swept incrementally — the thing that keeps this path off the
     * hand's back (Phase 28, the Nomad of 2026-09-18).
     *
     * It began as `GraphiteGrain.of(points, …, prefix = true)` on the whole stroke at every
     * MotionEvent, which re-decides every station already on the panel in order to find the
     * one or two that are new: a 1252-event slow stroke cost **4352 ms of grain on the UI
     * thread**, 3.5 ms an event and climbing with the length, felt as the ink dragging behind
     * the nib. (Toning and posting the batch were under a millisecond an event throughout —
     * the grain was the whole of it.) A [GraphiteGrain.Sweep] resumes instead, so a stroke a
     * thousand samples long decides each of its stations once.
     *
     * Null between contacts, and **null again after every commit** — a mid-contact commit is
     * a real thing here (the exclusion-zone split in `appendDrawPoints` commits a fragment
     * and restarts the capture buffer with a fresh pending id), and a sweep that carried on
     * across one would be laying the old fragment's stations against the new fragment's
     * points. Minted lazily on the next sample, with that contact's width and seed.
     */
    private var liveSweep: GraphiteGrain.Sweep? = null

    /** How many of this stroke's flecks are already on the panel — the log's measure of the
     *  contact, and no longer an index into anything: what [liveSweep] hands back IS the new
     *  graphite, so there is nothing left to slice. */
    private var laidFlecks = 0

    /** Everything this contact has painted, in view coordinates — what to clear at pen-up
     *  and what to re-tone if the contact is cancelled. */
    private val liveRect = Rect()

    /** Whether this contact is previewing directly (latched at ACTION_DOWN, like the
     *  engine's other contact latches, so the answer cannot change under it mid-stroke). */
    private var contactDirect = false
    private var liveEvents = 0

    /** Where the view sits on screen, read once per contact: the panel's coordinates are
     *  the screen's, and a mid-layout read lies. */
    private val contactScreenLoc = IntArray(2)

    /** Flatten/tone scratch, grown as rects demand: the batch's pixels, the flattened RGB,
     *  and the levels handed to the panel. */
    private var tonePix = IntArray(0)
    private var toneRgb = IntArray(0)
    private var toneLevels = ByteArray(0)
    private val toneRect = Rect()
    private val toneScreenRect = Rect()

    /**
     * The stroke under the pen grew: lay whatever graphite is newly decidable, and show it.
     *
     * What comes back from [GraphiteGrain.Sweep.extend] is **only** the new flecks, which is
     * the whole economy of this path — a stroke a thousand samples long decides and
     * rasterises each of its flecks once, not once per sample — and it is sound only because
     * the sweep guarantees the earlier ones are exactly where they already are.
     */
    override fun onLiveStrokeExtended(points: List<StrokePoint>) {
        if (!contactDirect) return
        val mask = ensureLiveAlpha() ?: return
        liveEvents++
        val t0 = System.nanoTime()
        // Through the bake's own seams, even though both are identity on this path: a
        // preview that reaches the renderer by a different road is a preview that can drift.
        val baked = bakePoints(points, StrokeStyle.PENCIL)
        val sweep = liveSweep
            ?: GraphiteGrain.begin(penWidth, pendingStrokeSeed()).also { liveSweep = it }
        val grain = sweep.extend(baked)
        if (grain.count == 0 && laidFlecks == 0) {
            // Not yet decidable (the first ~50 px of arc): show the hand something NOW. A
            // provisional lay of the whole stroke so far — wrong in its first flecks and its
            // end cap, but under the nib — replaced wholesale the moment the sweep starts
            // laying. It is the one place the whole stroke is still swept per event, and it
            // costs nothing: a stroke that short has barely any stations to decide.
            val provisionalGrain =
                GraphiteGrain.of(baked, penWidth, pendingStrokeSeed(), prefix = false)
            if (provisionalGrain.count == 0) return
            provisional = true
            clearMaskRect(mask, liveRect)
            val rect = newFleckBounds(provisionalGrain, 0) ?: return
            layFlecks(provisionalGrain, 0, rect, mask)
            val shown = Rect(liveRect); shown.union(rect)
            liveRect.set(shown)
            toneAndPost(shown, mask)
            timeGrain += System.nanoTime() - t0
            return
        }
        if (grain.count == 0) return
        if (provisional) {
            // The sweep has begun: drop the provisional flecks and lay the true ones. This
            // first batch is everything the sweep has decided, from the mark's very start.
            provisional = false
            clearMaskRect(mask, liveRect)
            val rect = newFleckBounds(grain, 0) ?: run { laidFlecks = sweep.count; return }
            layFlecks(grain, 0, rect, mask)
            laidFlecks = sweep.count
            liveRect.union(rect)
            toneAndPost(Rect(liveRect), mask)
            timeGrain += System.nanoTime() - t0
            return
        }
        val rect = newFleckBounds(grain, 0) ?: run { laidFlecks = sweep.count; return }
        val t1 = System.nanoTime()
        timeGrain += t1 - t0
        val scratch = ensureBatch(rect.width(), rect.height()) ?: return
        val canvas = batchCanvas ?: return
        val save = canvas.save()
        canvas.clipRect(0, 0, rect.width(), rect.height())
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
        drawPencilGrain(canvas, grain, 0, penColor, penWidth)
        canvas.restoreToCount(save)
        laidFlecks = sweep.count
        mergeBatchIntoLive(scratch, rect, mask)
        liveRect.union(rect)
        toneAndPost(rect, mask)
        val dt = System.nanoTime() - t1
        timeTone += dt
        if (dt > maxTone) maxTone = dt
    }

    /** Rasterise flecks `[from, count)` into the batch scratch and merge them into [mask]. */
    private fun layFlecks(grain: GraphiteGrain.Grain, from: Int, rect: Rect, mask: ByteArray) {
        val scratch = ensureBatch(rect.width(), rect.height()) ?: return
        val canvas = batchCanvas ?: return
        val save = canvas.save()
        canvas.clipRect(0, 0, rect.width(), rect.height())
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
        drawPencilGrain(canvas, grain, from, penColor, penWidth)
        canvas.restoreToCount(save)
        mergeBatchIntoLive(scratch, rect, mask)
    }

    private fun clearMaskRect(mask: ByteArray, r: Rect) {
        if (r.isEmpty) return
        for (y in r.top until r.bottom) {
            val row = y * liveAlphaW
            java.util.Arrays.fill(mask, row + r.left, row + r.right, 0)
        }
    }

    private var provisional = false
    private var timeGrain = 0L
    private var timeTone = 0L
    private var maxTone = 0L

    /** The view-space rect the flecks `[from, count)` cover, padded for the fleck size and
     *  clipped to the view; null when none of it is on screen. */
    private fun newFleckBounds(grain: GraphiteGrain.Grain, from: Int): Rect? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in from until grain.count) {
            val x = grain.xy[i * 2]
            val y = grain.xy[i * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (minX > maxX) return null
        // A fleck is a round point of its own diameter, anti-aliased: half of the widest one,
        // and two px of slack on top. Cheap generosity outward, and a rect that misses a
        // fleck leaves graphite the panel never shows until the bake.
        val pad = ceil(GraphiteGrain.fleckPx(GraphiteGrain.LEVELS - 1, penWidth) / 2f).toInt() + 2
        toneRect.set(
            floor(minX).toInt() - pad,
            floor(minY).toInt() - pad,
            ceil(maxX).toInt() + pad,
            ceil(maxY).toInt() + pad,
        )
        return if (toneRect.intersect(0, 0, liveAlphaW, liveAlphaH)) toneRect else null
    }

    /** The batch's alpha, composited `SRC_OVER` into the contact's live layer — the same
     *  arithmetic a Canvas would do, on the one channel that matters. */
    private fun mergeBatchIntoLive(scratch: Bitmap, rect: Rect, mask: ByteArray) {
        val w = rect.width()
        val h = rect.height()
        ensureToneScratch(w * h)
        scratch.getPixels(tonePix, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val maskRow = (rect.top + y) * liveAlphaW + rect.left
            val row = y * w
            for (x in 0 until w) {
                val sa = tonePix[row + x] ushr 24
                if (sa == 0) continue
                val i = maskRow + x
                val da = mask[i].toInt() and 0xFF
                mask[i] = (sa + da * (255 - sa) / 255).toByte()
            }
        }
    }

    /**
     * Flatten [rect] the way the page will be seen and show it: white paper, the graphite
     * image over it, this contact's live flecks over that, and the ink image through
     * `DARKEN` — the same order and the same operator `drawCommittedContent` uses, because
     * anything else would be a second opinion about what the page looks like and pen-up
     * would be where the two met.
     */
    private fun toneAndPost(rect: Rect, mask: ByteArray) {
        val w = rect.width()
        val h = rect.height()
        val n = w * h
        ensureToneScratch(n)
        java.util.Arrays.fill(toneRgb, 0, n, WHITE_RGB)
        if (readRaster(RasterLayer.GRAPHITE, rect)) {
            for (i in 0 until n) {
                val p = tonePix[i]
                if (p ushr 24 != 0) toneRgb[i] = overWhite(p)
            }
        }
        val penR = Color.red(penColor)
        val penG = Color.green(penColor)
        val penB = Color.blue(penColor)
        for (y in 0 until h) {
            val maskRow = (rect.top + y) * liveAlphaW + rect.left
            val row = y * w
            for (x in 0 until w) {
                val a = mask[maskRow + x].toInt() and 0xFF
                if (a == 0) continue
                val d = toneRgb[row + x]
                val r = (penR * a + ((d ushr 16) and 0xFF) * (255 - a)) / 255
                val g = (penG * a + ((d ushr 8) and 0xFF) * (255 - a)) / 255
                val b = (penB * a + (d and 0xFF) * (255 - a)) / 255
                toneRgb[row + x] = (r shl 16) or (g shl 8) or b
            }
        }
        if (readRaster(RasterLayer.INK, rect)) {
            for (i in 0 until n) {
                val p = tonePix[i]
                if (p ushr 24 == 0) continue
                val ink = overWhite(p)
                val d = toneRgb[i]
                toneRgb[i] = (min(d ushr 16 and 0xFF, ink ushr 16 and 0xFF) shl 16) or
                    (min(d ushr 8 and 0xFF, ink ushr 8 and 0xFF) shl 8) or
                    min(d and 0xFF, ink and 0xFF)
            }
        }
        for (i in 0 until n) {
            toneLevels[i] = RattaPanelTone.levelOf(OPAQUE or toneRgb[i]).toByte()
        }
        toneScreenRect.set(rect)
        toneScreenRect.offset(contactScreenLoc[0], contactScreenLoc[1])
        panel.post(toneScreenRect, toneLevels, w)
    }

    /**
     * [layer]'s pixels for [rect] into [tonePix], zero-filled where the page image does not
     * reach (a page smaller than the view, or a layer nothing has landed on). False when
     * there is no image at all — the common case for ink on a pencil page, and worth the
     * early return: it is a page-sized bitmap that never gets allocated.
     */
    private fun readRaster(layer: RasterLayer, rect: Rect): Boolean {
        val bitmap = rasterFor(layer) ?: return false
        val w = rect.width()
        val h = rect.height()
        java.util.Arrays.fill(tonePix, 0, w * h, 0)
        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val right = rect.right.coerceAtMost(bitmap.width)
        val bottom = rect.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return false
        val offset = (top - rect.top) * w + (left - rect.left)
        bitmap.getPixels(tonePix, offset, w, left, top, right - left, bottom - top)
        return true
    }

    /** An unpremultiplied ARGB pixel composited over white paper, as `0xRRGGBB`. */
    private fun overWhite(argb: Int): Int {
        val a = argb ushr 24
        if (a == 0) return WHITE_RGB
        if (a == 255) return argb and 0xFFFFFF
        val r = ((argb ushr 16 and 0xFF) * a + 255 * (255 - a)) / 255
        val g = ((argb ushr 8 and 0xFF) * a + 255 * (255 - a)) / 255
        val b = ((argb and 0xFF) * a + 255 * (255 - a)) / 255
        return (r shl 16) or (g shl 8) or b
    }

    private fun ensureToneScratch(n: Int) {
        if (tonePix.size < n) tonePix = IntArray(n)
        if (toneRgb.size < n) toneRgb = IntArray(n)
        if (toneLevels.size < n) toneLevels = ByteArray(n)
    }

    /** The live alpha layer, sized to the view. Re-allocated when the view resizes; null
     *  before layout, which is when nothing can be drawn anyway. */
    private fun ensureLiveAlpha(): ByteArray? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val existing = liveAlpha
        if (existing != null && liveAlphaW == w && liveAlphaH == h) return existing
        liveAlphaW = w
        liveAlphaH = h
        return ByteArray(w * h).also { liveAlpha = it }
    }

    /** The batch scratch, at least [w] × [h]. Grown, never shrunk, and capped at the view
     *  because nothing bigger can be drawn. */
    private fun ensureBatch(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val existing = batchBitmap
        if (existing != null && existing.width >= w && existing.height >= h) return existing
        val bw = maxOf(w, existing?.width ?: 0).coerceAtMost(maxOf(liveAlphaW, w))
        val bh = maxOf(h, existing?.height ?: 0).coerceAtMost(maxOf(liveAlphaH, h))
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        batchBitmap = bitmap
        batchCanvas = Canvas(bitmap)
        return bitmap
    }

    /** A direct contact begins: nothing laid yet, nothing to clear, and the view's screen
     *  offset read once (the panel speaks screen coordinates). */
    private fun beginLivePreview() {
        liveEvents = 0
        laidFlecks = 0
        // The sweep itself is minted on the first sample: only then is there a pending
        // stroke id to seed it from without asking for one and throwing it away.
        liveSweep = null
        liveRect.setEmpty()
        getLocationOnScreen(contactScreenLoc)
    }

    /** Forget this contact's live flecks. The panel is not told: the caller either baked the
     *  same pixels (pen-up — the window's frame is the mirror) or re-tones the rect itself
     *  (a cancelled contact). */
    private fun clearLivePreview() {
        if (contactDirect) Log.i(
            TAG,
            "live preview: $liveEvents events, $laidFlecks flecks, rect $liveRect, grain " +
                "${timeGrain / 1_000_000} ms total, tone ${timeTone / 1_000_000} ms total " +
                "(max ${maxTone / 1_000_000} ms/event)",
        )
        provisional = false
        liveSweep = null
        timeGrain = 0; timeTone = 0; maxTone = 0
        val mask = liveAlpha
        if (mask != null && !liveRect.isEmpty) {
            for (y in liveRect.top until liveRect.bottom) {
                val row = y * liveAlphaW
                java.util.Arrays.fill(mask, row + liveRect.left, row + liveRect.right, 0)
            }
        }
        laidFlecks = 0
        liveRect.setEmpty()
    }

    /**
     * A cancelled direct contact: nothing committed, so the graphite on the panel
     * corresponds to nothing at all. Drop the live layer and re-tone what it covered from
     * the page images alone, which paints the paper back.
     */
    private fun dropLivePreview() {
        val mask = liveAlpha
        if (mask == null || liveRect.isEmpty) {
            clearLivePreview()
            return
        }
        toneRect.set(liveRect)
        clearLivePreview()
        toneAndPost(toneRect, mask)
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

    /**
     * A recognizer consumed the just-captured stroke (smart lasso / scribble erase):
     * the firmware already painted it but nothing committed — overlay ink that
     * corresponds to nothing in the app layer, the gesture-trace ladder's exact job.
     * Any strokes still pending bake are baked first by [releaseFirmwareOverlay]
     * (their pixels move to the app layer before the clear), and consume-path
     * repaints (selection box, erase redraw) supply the co-presented frames the
     * ladder's clears pair with. Runs at pen-up, inside the daemon's
     * stroke-finalization window — the immediate clear is usually eaten (law 2);
     * the timed retries are what actually wipe the trace (≤450 ms self-heal).
     */
    override fun onGestureStrokeConsumed() {
        super.onGestureStrokeConsumed()
        if (contactDirect) {
            // A direct pencil contact painted the panel itself and the daemon painted
            // nothing, so there is no overlay trace to chase with the ladder — but there IS
            // graphite on the panel belonging to a stroke that just became a gesture. Same
            // remedy as a cancel: drop the live layer and re-tone the paper back.
            dropLivePreview()
            return
        }
        releaseGestureTrace()
    }

    /** (Re-)start the timed clearAll + invalidate retries. Idempotent; each firing is
     *  invisible once the overlay and panel agree. */
    private fun armOverlayClearLadder() {
        overlayClearArmed = true
        overlayClearAttempt = 0
        overlayClearFlushedOnApproach = false
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
     * Extra armed-clear attempt outside the timed ladder. Does NOT disarm the ladder:
     * if this attempt is eaten too, the timed retries still run (they self-disarm via
     * [pendingBake] once new ink commits).
     *
     * Primary site: the **hover approach** ([flushArmedOverlayClearOnApproach]) — the
     * clear + frame reconcile while the pen is still in the air, so the wipe can never
     * pair with a frame presented after new contact ink starts. Issuing it at
     * ACTION_DOWN instead (the reference's site) visibly ate the first dashes of the
     * next lasso outline on the Nomad: the daemon paired the down-time clear with a
     * frame presented a beat into the trail. The down-time flush is kept only for
     * erase contacts, whose overlay ink is unwanted anyway.
     */
    private fun flushArmedOverlayClear() {
        // Ownership moved to another paper view mid-ladder: a clearAll now would wipe
        // THEIR live overlay ink (same stand-down the timed ladder runnable enforces).
        if (inkOwner !== this) return
        if (!pendingBake) {
            SupernoteInk.clearAll()
            invalidate()
        }
    }

    /** One hover-stream flush per ladder arming (hover moves arrive at input rate;
     *  re-clearing on each would spam the binder for nothing). */
    private var overlayClearFlushedOnApproach = false

    private fun flushArmedOverlayClearOnApproach(event: MotionEvent) {
        if (!firmware || !overlayClearArmed || overlayClearFlushedOnApproach) return
        if (event.actionMasked != MotionEvent.ACTION_HOVER_ENTER &&
            event.actionMasked != MotionEvent.ACTION_HOVER_MOVE
        ) {
            return
        }
        val t = event.getToolType(0)
        if (t != MotionEvent.TOOL_TYPE_STYLUS && t != MotionEvent.TOOL_TYPE_ERASER) return
        if (isPenDown) return
        overlayClearFlushedOnApproach = true
        flushArmedOverlayClear()
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

    // ── Lasso drag-move suppress (hover — overlay law 3) ─────────────────────

    private var lassoHoverSuppressed = false

    /**
     * A drag-move starting inside the selection box must not paint the firmware's
     * dashed trail — and the firmware latches pen state at contact start (law 3), so the
     * disable must be issued from the **hover** stream, before the tip lands. While the
     * stylus hovers over the selection box: full-screen disable; hovering back out
     * re-arms the trail pen. [applyToolToFirmware] resets the flag (any tool push
     * supersedes the transient suppress; the next hover event re-asserts it). The
     * ACTION_DOWN disable in [onTouchEvent] is only the no-hover backstop.
     */
    private fun updateLassoDragHoverSuppress(event: MotionEvent) {
        if (!firmware || tool != Tool.LASSO) return
        if (event.actionMasked != MotionEvent.ACTION_HOVER_ENTER &&
            event.actionMasked != MotionEvent.ACTION_HOVER_MOVE
        ) {
            return
        }
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        if (firmwareInkSuppressed || barrelDown) return // a stronger suppress owns the firmware
        val inside = selectionBoxContains(event.x, event.y)
        if (inside == lassoHoverSuppressed) return
        lassoHoverSuppressed = inside
        if (inside) fullScreenDisable() else applyToolToFirmware()
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

    /** Lasso-contact latches (ACTION_DOWN): an outline paints the firmware dash trail
     *  (wiped at lift via the ladder); a drag runs under full-screen disable (restored
     *  at lift). Mutually exclusive with [contactErasing]. */
    private var contactLassoOutline = false
    private var contactLassoDrag = false

    /** Whether the current contact has the firmware painting live INK (pen tool, not
     *  suppressed) — so a cancelled contact, which commits nothing, can still wipe the
     *  partial stroke the firmware already painted. */
    private var contactInking = false

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
                    // Mirror of the base's gesture classification. An erase contact is
                    // a handoff boundary: bake pending overlay ink FIRST so the
                    // software erase + redraw operates on a fully-baked page (the
                    // firmware natively wipes only its own overlay pixels).
                    contactErasing = !firmwareInkSuppressed && (
                        toolType == MotionEvent.TOOL_TYPE_ERASER ||
                            tool == Tool.ERASER ||
                            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                        )
                    // Armed gesture-trace clear: normally already flushed from the
                    // hover approach (flushArmedOverlayClearOnApproach — a down-time
                    // clear pairs with a frame presented into THIS contact's ink and
                    // eats it; seen wiping lasso-trail starts on the Nomad). Keep the
                    // down flush only for erase contacts, whose overlay ink is
                    // unwanted anyway.
                    if (contactErasing) {
                        if (overlayClearArmed) flushArmedOverlayClear()
                        releaseFirmwareOverlay()
                    }
                    contactLassoOutline = false
                    contactLassoDrag = false
                    contactInking = !contactErasing && tool == Tool.PEN && !firmwareInkSuppressed
                    // Latched like every other contact state: what previews this mark may
                    // not change under it half way through (a host arming the pen mid-stroke
                    // would otherwise leave half a mark on the panel and half on the overlay).
                    contactDirect = contactInking && directPencil
                    if (contactDirect) beginLivePreview()
                    Log.i(
                        TAG,
                        "contact: direct=$contactDirect inking=$contactInking panel=${panel.isOpen} " +
                            "mode=$pageMode style=$penStyle tool=$tool suppressed=$firmwareInkSuppressed",
                    )
                    // The lasso eraser (0.1.28) is always an outline contact — no box, no
                    // drag; its x-trail rides the same gesture-trace ladder at lift.
                    if (!contactErasing && tool == Tool.LASSO_ERASER) contactLassoOutline = true
                    if (!contactErasing && tool == Tool.LASSO) {
                        if (selectionBoxContains(event.x, event.y)) {
                            contactLassoDrag = true
                            // Backstop only: the REAL suppress happened on the hover
                            // stream before the tip landed (law 3 — a disable issued
                            // here is too late for this contact's first dashes, but
                            // heals a no-hover contact for the rest).
                            fullScreenDisable()
                        } else {
                            contactLassoOutline = true
                        }
                    }
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
            } else if (contactLassoOutline) {
                // The dashed trail (a tap paints a dash dot too) corresponds to nothing
                // in the app layer — wipe it with the proven gesture-trace ladder. The
                // base has already drawn the selection box by now (super ran first).
                releaseGestureTrace()
            } else if (contactLassoDrag) {
                // Drag contact over: the base finished/cancelled the drag inside super
                // (isSelectionDragActive is false again), so this push restores the
                // disable areas + the armed lasso trail pen after the drag suppress.
                applyToolToFirmware()
            } else if (contactDirect) {
                // No overlay ink to wipe — the daemon was off. A normal lift has already
                // baked and presented (bakeAfterCommit); a cancel commits nothing, so the
                // graphite on the panel belongs to no stroke and has to come back off.
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) dropLivePreview()
            } else if (contactInking && event.actionMasked == MotionEvent.ACTION_CANCEL) {
                // A cancelled draw contact commits nothing (the base dropped its
                // points), but the firmware already painted the partial stroke —
                // overlay ink corresponding to nothing in the model. Wipe it with the
                // proven gesture-trace ladder.
                releaseGestureTrace()
            }
            contactErasing = false
            contactLassoOutline = false
            contactLassoDrag = false
            contactInking = false
            contactDirect = false
        }
        return handled
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        compensateRegistration(event)
        rearmOnPenApproach()
        flushArmedOverlayClearOnApproach(event)
        updateBarrelSuppress(event)
        updateLassoDragHoverSuppress(event)
        return super.onHoverEvent(event)
    }

    // Some stacks report button changes as ACTION_BUTTON_PRESS/RELEASE generic events
    // rather than a buttonState change on the hover stream — catch those too. A
    // pointer-source hover already ran the block above in onHoverEvent and reaches
    // here as the SAME MotionEvent (this view is not hoverable, so dispatch falls
    // through) — skip it, or compensateRegistration's in-place offsetLocation doubles
    // the registration shift and every suppressor runs twice per sample.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isPointerSourceHover(event)) {
            compensateRegistration(event)
            rearmOnPenApproach()
            flushArmedOverlayClearOnApproach(event)
            updateBarrelSuppress(event)
            updateLassoDragHoverSuppress(event)
        }
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
    //
    // loadPageRaster and swapPageRaster (0.1.25 / 0.1.29) need no override either, and
    // for two different reasons worth keeping straight — this is verified, not a gap:
    //
    //  - loadPageRaster is a content swap, and a host turning a page calls
    //    clearForContentSwap first: the overlay is baked and released above, under the
    //    swap law, before any pixel moves. Called on its own it still lands correctly,
    //    because it ends in redrawCommitted.
    //  - swapPageRaster is an undo, which arrives with no swap in front of it — the
    //    artist draws a mark and takes it back, so the firmware overlay is very likely
    //    still showing that mark's live ink. redrawCommitted's own pendingBake guard is
    //    what covers it, in the order the law requires: the page image (already without
    //    the undone mark, the swap having run) is re-recorded, THEN clearAll drops the
    //    overlay, THEN the frame presents and the ladder arms. The overlay ink of the
    //    undone mark goes with it and nothing of it survives on the panel.
    //
    // Two rasters (0.1.39) change neither reason: a layer is which bitmap the call writes,
    // and both arguments above are about when the overlay is dropped relative to the
    // re-record, which the flatten in drawCommittedContent does not touch.

    override fun releaseRender() {
        // Host chrome touch — the Ratta analogue of releasing the EPD overlay: bake
        // pending ink and clear so chrome paints clean.
        if (firmware) releaseFirmwareOverlay()
    }

    // ── Lifecycle — process-global firmware session ──────────────────────────

    /** (Re-)claim the firmware pen and turn on full-UI ink. Idempotent, safe to call often. */
    private fun setupFirmwareInk() {
        if (!firmware) return
        // The panel driver, before the tool push: [applyToolToFirmware] asks [directPencil]
        // whether to arm the needle or disable the daemon, and that answer depends on this.
        // Idempotent, and a refusal is remembered — every focus gain runs this method.
        if (isRattaDevice()) {
            panel.open(maxOf(screenW, width), maxOf(screenH, height))
        }
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

    /** Let go of the panel: the fd, the mapping, the display thread and the live layer.
     *  Idempotent, and never re-opened for this view — a dead view has nothing to preview. */
    private fun releasePanel() {
        clearLivePreview()
        panel.close()
        liveAlpha = null
        liveAlphaW = 0
        liveAlphaH = 0
        batchCanvas = null
        batchBitmap = null
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
        releasePanel()
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
        fullScreenDisable()
        SupernoteInk.enableFullUiAuto(context, false)
        // The handoff IS this view's teardown (0.1.2). Until 0.1.1 the token stayed ours so a
        // successor's claim would overwrite it and our later teardowns would skip — but the
        // successor may live in ANOTHER PROCESS (Notesprout Paper's scratch pad), where this
        // static is invisible: our focus-loss and `release()` teardowns then re-sent
        // `enableFullUiAuto(false)` + the full-screen disable AFTER the caller had reclaimed
        // (≈ 200 ms later, measured on a Nomad) — its session stayed live but the panel
        // dropped out of full-UI-auto, so every drag / app frame repainted with the slow
        // waveform until a later re-arm. Dropping the token here makes those teardowns no-ops
        // (ownership re-asserts on our own `resumeDrawing()` / focus gain if the launch falls
        // through); the full-screen disable above is what detach would have done for the
        // "successor never claims" edge.
        inkOwner = null
        Log.i(TAG, "firmware ink released for handoff")
    }

    override fun release() {
        overlayClearArmed = false
        removeCallbacks(overlayClearRunnable)
        releasePanel()
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
