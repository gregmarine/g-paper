package com.symmetricalpalmtree.gpaper.ratta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import com.symmetricalpalmtree.gpaper.core.PageMode
import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.canvas.CanvasPaperView
import com.symmetricalpalmtree.gpaper.core.canvas.LassoTrailChrome
import com.symmetricalpalmtree.gpaper.core.canvas.PencilInk
import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

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
        /**
         * Whether the Supernote panel ever shows a mark in its true tone (Phase 41, 0.1.55:
         * **no**). The dither is the one picture this panel shows well: the artist, with the
         * dithered page and the settled page side by side on the Mac and on the glass — *"the
         * dither looks great on the device, and the true tone looks great on the Mac. But the
         * dither looks awful on Mac and the true tone doesn't quite look right on the
         * device"* — asked for the device always dithered, pencil and pen alike, and the
         * export always true tone. So every display flatten dithers, a loaded page is shown
         * dithered, the runs a bake leaves are not kept, and [settleDisplay] is a no-op; the
         * page images, the covers and `renderToBitmap` keep the true greys, exactly as
         * before (Phase 28's decision 7 stands). Phases 31–34's tone display stays in the code
         * behind this one flag; flipping it back restores every settle of Phase 37.
         */
        const val DISPLAY_SETTLES = false

        const val TAG = "GPaperRatta"

        /** How long after the last smudge contact ends the window mirror is presented on the
         *  direct path — long enough to ride out a chattering tip switch (~250 ms a contact). */
        const val SMUDGE_MIRROR_DELAY_MS = 400L

        /** Floor for the firmware eraser EMR size (`radius * 50`, min 400 — PoC-validated). */
        const val ERASER_EMR_MIN = 400

        /** EMR size for the firmware lasso trail (the DASH pen) — the exact size the
         *  0…31 sweep measured that code rendering at. Independent of the ink pen's
         *  width mapping: the trail is chrome, not ink. */
        const val LASSO_TRAIL_EMR = 300

        /**
         * The app-painted lasso trail on a direct page (Phase 42, the user's decision 2):
         * [LassoTrailChrome] — a 2 px black dashed line, 12 on / 8 off, for the lasso, and
         * since Phase 43 (0.1.57) a stream of 2 px x-marks every 10 px, 5 px arms, for the
         * lasso eraser — the daemon's own two trails, and the chrome every other engine
         * draws in its window. Aliased, because the panel is dithered anyway and a crisp
         * mark reads better than a soft one there.
         */
        const val TRAIL_WIDTH_PX = LassoTrailChrome.WIDTH_PX
        const val TRAIL_DASH_ON_PX = LassoTrailChrome.DASH_ON_PX
        const val TRAIL_DASH_OFF_PX = LassoTrailChrome.DASH_OFF_PX
        const val TRAIL_CROSS_PITCH_PX = LassoTrailChrome.CROSS_PITCH_PX
        const val TRAIL_CROSS_ARM_PX = LassoTrailChrome.CROSS_ARM_PX

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

        /**
         * The two panel levels the direct path ever sends: `0x00` black and `0x0f` white.
         *
         * Nothing in between, on purpose (Phase 28, the third walk). Both of these land on
         * the panel's **first** frame, where every grey between them arrives by way of black
         * and lightens out of it over the next second — so a dither of the page is the one
         * picture this panel can show truthfully while the pen is moving, and a pencil is
         * exactly the thing that survives being dithered.
         */
        const val LEVEL_BLACK: Byte = 0x00
        const val LEVEL_WHITE: Byte = 0x0f

        /** How many pixels one band of a dither rebuild covers — a megabyte of `Int`
         *  scratch, three times over. A whole 1404 × 1872 page is then about eight bands
         *  and allocates nothing after the first one. */
        const val DITHER_BAND_PX = 262144

        /** The two ends of the display image's byte — **black coverage**, 0…255, which is
         *  what the band kernel writes and what an `ALPHA_8` bitmap's own rows hold (the
         *  bitmap is drawn in black, so a byte is how black that pixel is). A dithered pixel
         *  is one of these two; a baked-ink pixel is its true tone between them (Phase 31). */
        const val DITHER_ON: Byte = -1 // 0xFF
        const val DITHER_OFF: Byte = 0

        /** The panel level for a display byte: coverage `c` is grey `255 − c` through the
         *  compositor's own grey → level table — so a dithered `0xFF` is [LEVEL_BLACK], a
         *  `0` is [LEVEL_WHITE], and a baked-ink tone is the level the compositor would
         *  have given that grey anyway. Tabled once: it is asked per pixel. */
        private val LEVEL_OF_COVERAGE = ByteArray(256) { RattaPanelTone.level(255 - it).toByte() }

        /**
         * From what share of the page a rect rebuild lands through one whole-bitmap
         * `copyPixelsFromBuffer` instead of a `setPixels` of itself — see [DitherCost].
         *
         * An eighth, which is a starting value and not a measurement: the copy is a
         * single memcpy of about 2.6 MB on a Nomad page and the `setPixels` path is an
         * expansion loop plus Skia reading one byte in four, so the crossing is
         * somewhere well below half a page and nothing has been profiled on a device to
         * say where. The log line in [regenDither] is what a walk judges it by.
         */
        const val DITHER_WHOLE_COPY_FRACTION = 0.125f

        /**
         * How many of a mark's runs may land through their own `setPixels` before the
         * whole page is copied once instead — see [DitherCost.preferWholeCopyForRuns].
         *
         * Eight, and a starting value like the fraction above: `setPixels` costs
         * something fixed per call whatever the rect, one page copy is a few ms, and
         * somewhere past a handful of small rects the calls alone outweigh it. A large
         * pencil scribble is up to sixty-four runs (the core's cap on a mark's dirty
         * rects), which is eight times over — and **651 ms** of pen-up on a Nomad, with
         * not one of those rects slow enough to reach the log line.
         */
        const val DITHER_MAX_SETPIXELS_RECTS = 8

        /** How long a *rect* rebuild has to take before it is worth a log line. A page
         *  turn is always logged; a rect is the ordinary cost of a mark landing, and only
         *  one that a hand could feel is news. Raised from 5 ms once per-run announcement
         *  made the ordinary rect small (2026-09-19). */
        const val DITHER_SLOW_RECT_MS = 20L

        /**
         * Whether a page that has just been **loaded** is shown through the panel before
         * the window presents it (2026-09-19, unwalked).
         *
         * The hypothesis, and it is a hypothesis: the user's 0.1.43 finding was that the
         * rubber and the pen direct are solid, and that what ghosts now is a **page flip**
         * on the sketch face — hardly ever on the notebook face. The HWC picks a waveform
         * per frame from that frame's own content (`getBestDisplayMode` in `libeinkutils`).
         * A dithered raster page is pure black and white with no grey anywhere in it, which
         * is exactly the content that reads as "send it two-level" — the fast waveform, and
         * the one that ghosts. The notebook face's anti-aliased greys ask for the clean
         * sixteen-level one and get it.
         *
         * So the loaded page is put on the glass by this path first, through the same
         * `MODE_GREY16` the flecks go out on, and the compositor's own post a moment later
         * finds pixels identical to the ones already there and drives nothing.
         *
         * A full framework refresh at every turn cured it too and the user rolled that
         * back — *"a bit much"*. Whether this is the quiet form of the same cure is the
         * user's walk to decide; `false` restores 0.1.43 exactly.
         */
        const val PRESENT_LOADED_PAGE_VIA_PANEL = false

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
     * inert when the driver refused: see [EbcPanel] and [directRaster].
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
     *  anywhere: the NONE tool, the stylus smudge (0.1.60 — the nib moves graphite the
     *  app posts itself; a needle under it would be stray ink), and the whole of a
     *  selection drag-move contact (the drag layer is app-drawn; a firmware dash trail
     *  under it would be stray ink). */
    private val firmwareInkSuppressed: Boolean
        get() = tool == Tool.NONE || tool == Tool.SMUDGE || isSelectionDragActive

    override var tool: Tool
        get() = super.tool
        set(value) {
            val changed = super.tool != value
            // A tool change cancels the outline under the pen (the base clears its
            // points); the trail this view painted onto the panel goes with it.
            if (changed && contactTrail) wipeTrail()
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
            if (changed) {
                // The base's setter is a content swap: the page is empty on both sides of
                // the flip, so the dither of the old one is stale and there is nothing yet
                // to put in its place. Dropped, never cleared in place — the committed
                // display list is still holding it, and the panel keeps those pixels until
                // the host loads the page the new mode understands (see [ditherDisplay]).
                dropDither()
                dropCommittedPage()
                // Anything posted against the old page goes with it: a rebuild of pixels
                // that are gone.
                removeCallbacks(ditherRebuild)
                ditherCoalescer.reset()
                // The page's mode is one of [directRaster]'s preconditions — a flip
                // changes which thing draws the live ink for EVERY tool now, not only for
                // the pencil, and the base's setter knows nothing of the firmware. So this
                // is a full tool push rather than a pen re-arm: a page flipped to raster
                // with the rubber armed would otherwise leave the daemon's own eraser
                // wiping the panel we are about to paint.
                rearmForPageMode()
                announceDirectPath()
            }
        }

    override var directInk: Boolean
        get() = super.directInk
        set(value) {
            val changed = super.directInk != value
            if (changed && firmware) {
                // Strokes the overlay is still showing bake and clear under the old rule
                // before the rule changes: once the page is ours there is no overlay to
                // hand them off from, and once it is the daemon's again the committed
                // image must not be what the window records.
                releaseFirmwareOverlay()
            }
            super.directInk = value
            if (!changed) return
            if (!firmware) return
            if (pageMode == PageMode.STROKE) {
                if (value) {
                    // The flatten base does not exist yet: a whole rebuild renders it,
                    // dithers it and re-records the window off it, deferred like a load.
                    refreshDitherDisplay()
                } else {
                    dropCommittedPage()
                    dropDither()
                    removeCallbacks(ditherRebuild)
                    ditherCoalescer.reset()
                    redrawCommitted() // the vector record, true greys, the daemon's page again
                }
            }
            // Which thing paints the live ink changed for every tool: a full tool push,
            // as a page-mode flip is.
            rearmForPageMode()
            announceDirectPath()
        }

    override var eraserRadius: Float
        get() = super.eraserRadius
        set(value) {
            super.eraserRadius = value
            if (firmware && inkOwner === this && tool == Tool.ERASER && !barrelDown && !direct) {
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
            // A direct raster page wants the daemon OFF, and this is the path every
            // style/colour/width change comes down — so leaving the page re-arms the needle
            // and arriving on it takes the needle away, with no tool boundary needed.
            if (direct) fullScreenDisable() else applyPenToFirmware()
        }
    }

    /**
     * Re-push the armed tool after a page-mode flip (0.1.43): the whole tool, not just the
     * pen, because [directRaster] decides for every one of them now.
     *
     * A transient suppress stands: a held barrel and the two app-drawn modes already own a
     * full-screen disable, and [applyToolToFirmware] would clear their flags and hand the
     * daemon back paper it is meant to be off. The hover stream re-asserts them anyway.
     */
    private fun rearmForPageMode() {
        if (!firmware || inkOwner !== this) return
        if (firmwareInkSuppressed || barrelDown) return
        applyToolToFirmware()
    }

    /**
     * One line, when a raster page opens on a panel that is ours: **what goes through it**.
     *
     * `EbcPanel` already says whether the driver opened (`panel: direct` / `panel: needle`)
     * and that is a fact about the session; this is a fact about the page, and after 0.1.43
     * they are different questions — the panel can be open while the page is a stroke-mode
     * one the daemon owns entirely. A demo or a host reads it to know which behaviour it is
     * looking at, which is the same reason the panel's own line exists (Phase 28's fourth
     * deviation: a log line rather than a public probe widened to serve one row of chrome).
     */
    private fun announceDirectPath() {
        if (directRaster) Log.i(TAG, "direct: pencil+pen+rubber")
        else if (directStroke) Log.i(TAG, "direct: ink+eraser+trail (stroke page)")
    }

    /**
     * Whether **this page** is ours to paint rather than the daemon's (Phase 29).
     *
     * Three clauses and no more, which is the change 0.1.43 made: the panel must have
     * opened, and the page must be a raster one, because everything this path draws is
     * flattened against the page images and there is nothing to flatten against in stroke
     * mode. It no longer asks which tool or which style is armed. 0.1.41's `directPencil`
     * did — the pencil previewed itself and the pen, the rubber and the lasso stayed on the
     * needle — and the artist's word after the 0.1.42 walk was to take the whole page:
     * *"For the sketch face, it would be great to have all go through our own panel
     * implementation."*
     *
     * So the daemon is **full-screen-disabled for every tool on a direct raster page**, and
     * there is no overlay ink anywhere on it: no `pendingBake`, no clear ladder, nothing to
     * hand off at a boundary. A stroke-mode page is untouched and still the daemon's
     * entirely.
     */
    private val directRaster: Boolean
        get() = DirectGate.raster(firmware, panel.isOpen, pageMode)

    /**
     * Whether **this stroke page** is ours to paint (Phase 42, 0.1.56) — the host opted in
     * ([directInk]) and the panel is open. The flatten base a stroke page lacks is made
     * here: [committedPage], the committed picture as an image of its own, kept current
     * by every path that changes it (see [redrawCommitted]). The daemon is full-screen-
     * disabled across the page exactly as on a direct raster one; where the panel refused
     * the page is the daemon's with every law intact, so a host may opt in unconditionally.
     */
    private val directStroke: Boolean
        get() = DirectGate.stroke(firmware, panel.isOpen, pageMode, directInk)

    /** Either direct page — the daemon off, every post ours. */
    private val direct: Boolean
        get() = directRaster || directStroke

    /**
     * Which styles a direct **stroke** page previews live — the pen family only: `PEN` and
     * the two that render as it, the uniform round-capped line whose segments join exactly
     * (see [directStyle]). The pencil is not among them here: on a stroke page its bake is
     * the vector render, a second derivation of the grain the live layer laid, and the
     * writing faces this path exists for offer no pencil (arc 49, decision 10). Every other
     * style appears at pen-up, as on a direct raster page.
     */
    private fun directInkStyle(style: StrokeStyle): Boolean = when (style) {
        StrokeStyle.PEN, StrokeStyle.BRUSH, StrokeStyle.CALLIGRAPHY -> true
        else -> false
    }

    /**
     * Whether a mark in [style] is one this path can **preview**, fleck by fleck or segment
     * by segment, as it is drawn.
     *
     * `PENCIL` is [GraphiteGrain]'s scatter, which has a prefix-stable texture and can be
     * laid one fleck at a time ([GraphiteGrain.Sweep]). The pen path — `PEN` and the two
     * styles that render as it — is a uniform round-capped line, and round caps are what
     * make a segment drawn now join exactly with the segment drawn next, so the live layer
     * can be built up a MotionEvent at a time and then *be* the mark.
     *
     * Nothing else can, and the others are not bent to fit: a `MARKER` is one translucent
     * coverage pass over the whole path, a `DASH`'s pattern is a property of the whole
     * path, a `CROSS`'s marks are sampled along it. Those still commit exactly as they
     * always did (the base composites them) — they simply appear at pen-up, because on this
     * page the daemon has nothing to preview them with. None of them is offered by SN, and
     * bending a style's appearance to make it previewable would be the [StrokeStyle.PENCIL]
     * mistake of Phase 11 all over again.
     */
    private fun directStyle(style: StrokeStyle): Boolean = when (style) {
        StrokeStyle.PENCIL, StrokeStyle.PEN, StrokeStyle.BRUSH, StrokeStyle.CALLIGRAPHY -> true
        else -> false
    }

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
        if (direct) {
            // Atelier's arrangement: with the app painting the panel itself, the daemon must
            // not paint over it. A full-screen disable is the only "firmware off" switch
            // there is (the disable areas are screen-space), and it is issued from the same
            // tool push the needle, the firmware eraser or the dash trail would have been
            // armed from — so the hand-over happens wherever a tool, style, colour, width or
            // page mode changes. **Every tool** (0.1.43): the rubber shows through the panel
            // now, and the lasso's trail would otherwise be firmware ink on a page whose
            // pixels we own. The lasso's trail is painted by this view instead (Phase 42,
            // [onLassoTrailExtended]) — the dashed outline posted like a stroke segment and
            // wiped by re-presenting the committed picture under it, on a raster page and
            // a stroke page alike. (Until 0.1.55 a lasso on a direct raster page had no
            // live trail at all; nothing that shipped lassoed there.)
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
        // The same zones, as the quads every panel post is cut around ([postLevels]).
        exclusionQuads = IntArray(rects.size * 4).also { q ->
            for ((i, r) in rects.withIndex()) {
                q[i * 4] = r.left; q[i * 4 + 1] = r.top; q[i * 4 + 2] = r.right; q[i * 4 + 3] = r.bottom
            }
        }
        // Apply live unless a full-screen disable owns the areas right now (suppressed
        // mode or held barrel) — leaving those re-applies via applyToolToFirmware.
        if (firmware && inkOwner === this && !firmwareInkSuppressed && !barrelDown) {
            // A direct page owns a full-screen disable; re-sending the complement bands
            // here would hand the daemon back the paper it is meant to be kept off.
            if (direct) fullScreenDisable() else applyDisableAreas()
        }
    }

    /** [exclusionRects] as `l, t, r, b` quads — what [PanelClip] cuts a post around. */
    private var exclusionQuads = IntArray(0)

    // ── Deferred bake & overlay handoff ──────────────────────────────────────

    /**
     * A mark committed on a direct **stroke** page (Phase 42): lay it into [committedPage]
     * over its runs, dither those runs, and let the window mirror — the raster path's
     * shape, with the committed picture where the page images were.
     *
     * A contact this view previewed lays its live layer by the same integer `SRC_OVER`
     * the raster bake uses ([compositeLiveInto]), so the page holds precisely the pixels
     * the panel showed and the mirror is exact at the one moment it matters. A style this
     * path did not preview is re-rendered from the vector over the same runs. Either way
     * the whole-page render is not done here — the runs are the mark's ink, never its
     * bounding box (0.1.33's rule), and the redraw that follows finds the image current.
     */
    override fun bakeAfterCommit(stroke: Stroke) {
        if (!firmware || !directStroke) {
            bakeAfterCommit()
            return
        }
        val runs = rasterDirtyAlong(stroke)
        val page = ensureCommittedPage()
        if (page != null && runs.isNotEmpty()) {
            val mask = if (contactDirect) liveLayer(contactLayer) else null
            if (mask != null) {
                for (r in runs) compositeLiveInto(page, mask, r, contactPenColor)
            } else {
                for (r in runs) renderCommittedPage(r)
            }
            // A pending whole rebuild covers these runs and will consume the mark.
            if (!ditherCoalescer.scheduled) regenDitherRuns(runs)
            committedCache.markCurrent()
        }
        redrawCommitted()
        if (contactDirect) clearLivePreview()
    }

    override fun bakeAfterCommit() {
        if (firmware && (contactDirect || directRaster)) {
            // A direct raster page has nothing to defer: the daemon is disabled across the
            // whole of it, so there is no overlay copy of any ink to keep showing and none
            // to drop. Record and present at once — the window's own pixels become the
            // mirror of what the panel is already displaying, dot for dot ([DitherFlatten]),
            // and the panel does not move. Deferring instead would leave the mark live on
            // the panel and absent from every frame until some later boundary, which is a
            // mark that vanishes if anything at all repaints. (True of a style this path
            // cannot preview too — it appears at this bake, which is the one moment it
            // could have; see [directStyle].)
            super.bakeAfterCommit()
            if (contactDirect) clearLivePreview()
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
        // A whole-page dither rebuild is posted and this frame would present the page
        // half-built (or, after a content swap, blank). The runnable rebuilds and then
        // redraws — one present, of one correct picture. See [DitherCoalescer].
        if (ditherCoalescer.deferRedraw) return
        if (!firmware) {
            super.redrawCommitted()
            return
        }
        if (directStroke) {
            // The committed image is the flatten base and the window records the dither of
            // it, so a redraw is one of two things. A change that was laid already — a
            // mark's runs, an erase batch, an undone stroke — left the image current
            // ([committedCache]), and the window need only mirror it. Anything else — a
            // load, a template, the host's own content — rebuilds the whole page, deferred
            // and coalesced exactly as a raster load is: the page-swap law is five calls
            // and one rebuild.
            if (committedCache.take() && committedPage != null) {
                if (recordCommitted()) invalidate()
            } else if (ditherCoalescer.onWholePage() == DitherCoalescer.Action.SCHEDULE_WHOLE) {
                post(ditherRebuild)
            }
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
     * The raster eraser's redraw cadence on Supernote — **end-only where the panel is ours,
     * [RASTER_ERASE_REDRAW_MS] where it is not.**
     *
     * The 16 ms is the measurement (its KDoc holds the walk) and it stands for the needle
     * fallback, where a mid-sweep window redraw is the only way the artist sees graphite
     * lifting under the rubber. On the direct path there is a better one: the rubbed
     * corridor goes **straight into the panel** as each batch lands
     * ([onRasterErasedBatch]), so a window frame every 16 ms would be a second, later
     * opinion about pixels the panel already has — and every one of them is a whole app
     * frame the compositor rewrites over the top of. One `redrawCommitted` at the end of
     * the sweep ([finalizeEraseRedraw], which runs whether or not a mid-sweep redraw ever
     * did) is the mirror, exactly as pen-up is the mirror of a mark.
     *
     * Onyx keeps a frame's cadence and asks its own panel for the corridor
     * (`presentRasterEraseProgress`) — the same shape of answer, reached on its hardware's
     * own terms. The seam goes on earning its keep.
     */
    override val rasterEraseRedrawIntervalMs: Long
        get() = if (directRaster) rasterEraseRedrawEndOnly else RASTER_ERASE_REDRAW_MS

    /** End-only on a direct stroke page for the same reason: each erase batch is shown
     *  on the panel as it lands ([onCommittedStrokesChanged]), and the window mirrors once
     *  at the sweep's end. The base's cadence stands wherever the daemon previews. */
    override val strokeEraseRedrawIntervalMs: Long
        get() = if (directStroke) rasterEraseRedrawEndOnly else super.strokeEraseRedrawIntervalMs

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
     * `PENCIL` bakes **upright, always** on this device (Phase 39, 2026-09-21) — the user's
     * word: *"remove the side pencil shading completely. Just normal pencil regardless of
     * tilt."* The lean the digitizer reports is thrown away for the pencil, so a leaned pen
     * lays exactly the mark an upright one does: no flank, and none of the round lead's own
     * tilt bloom either.
     *
     * History, because the decision has moved three times and the file should say so once:
     * 0.1.35 zeroed it while the firmware needle previewed (a bake that widened ~11× under a
     * line that could not); Phase 28 decision 5 wrote *"the Supernote pencil stays upright"*;
     * Phase 36 relaxed it on the direct panel path for the flank ([GraphiteGrain.Lead.FLANK],
     * the side-of-the-lead shading of arc 47); Phase 38 tried to give that flank the point's
     * grain and could not afford it live; Phase 39 takes the flank out altogether. The flank
     * stays in [GraphiteGrain] as an opt-in no engine uses.
     */
    override fun bakeTilt(style: StrokeStyle, tilt: Float): Float =
        if (style == StrokeStyle.PENCIL) 0f else tilt

    /** The lead this engine's pencil draws with: the round lead, upright — see [bakeTilt]. */
    private val pencilLead: GraphiteGrain.Lead get() = GraphiteGrain.Lead.ROUND

    /**
     * A leaned pencil's mark reaches far past its own width, so its dirty region must too —
     * [GraphiteGrain.reach] at the furthest the pen leaned anywhere along the stroke, which
     * is the bound the sweep's own causal filters stay inside.
     *
     * Doubled, because the base's default is `stroke.width` against a half-width of half
     * that: the same factor of two the round lead has always been padded by, kept rather
     * than tightened, since this is the rect the **bake** composites within and a mark
     * clipped there is a mark half written. An upright pencil therefore lands within a
     * pixel or two of the old number and only a stroke that actually leaned costs more.
     */
    override fun rasterDirtyWidth(stroke: Stroke): Float {
        if (stroke.style != StrokeStyle.PENCIL) return stroke.width
        var reach = 0f
        for (p in stroke.points) {
            val r = GraphiteGrain.reach(stroke.width, bakeTilt(stroke.style, p.tilt), pencilLead)
            if (r > reach) reach = r
        }
        return maxOf(stroke.width, 2f * reach)
    }

    // ── What the digitizer actually reports (Phase 36) ───────────────────────

    /**
     * **The Ratta HAL does not follow Android's axis contract**, and nothing but a hand and
     * a CSV was ever going to say so: `AXIS_TILT` carries signed **tilt-X in degrees** and
     * `AXIS_ORIENTATION` signed **tilt-Y in degrees**, both live, both in the *panel's*
     * frame. The polar lean from vertical is their hypotenuse — the same quantity, and the
     * same reading, the NoteAir5C measurement found on BOOX (Phase 11).
     *
     * Read as written, `AXIS_TILT` is radians, so a raw `30` meant 1719° and ran off the end
     * of every curve in [GraphiteGrain]. It hid for a year because the Nomad's sign is
     * negative and clamped to upright; on the Manta it was positive and saturated, which is
     * the 10–15× bloom of 2026-09-17 that Phase 22 answered by taking the lean away
     * altogether. **A units bug and a policy decision are not the same shape of thing, and
     * one was mistaken for the other** — the fix belonged here, one line, and instead the
     * pencil lost its flank for two releases.
     *
     * Measured by the user's hand on both panels, 2026-09-19 (`probe-tilt/README.md`):
     * upright 7–10°, writing 29–39°, shading 54–61°, and the digitizer stops tracking at
     * 62° (Nomad) / 72° (Manta).
     */
    override fun sampleTilt(rawTilt: Float, rawOrientation: Float): Float =
        Math.toRadians(hypot(rawTilt.toDouble(), rawOrientation.toDouble())).toFloat()

    /**
     * Which way the pen is leaning, turned out of the panel's frame into the screen's — see
     * [EbcGeometry.screenAzimuth] for why the turn is the hand's finding and not a
     * derivation, and [StrokePoint.azimuth] for the convention.
     *
     * `0` while the panel driver has not opened, because that is where the quarter turn is
     * read from and a direction we cannot place is a direction we do not report (Phase 11's
     * rule, and the flank is off on that path anyway — [bakeTilt] zeroes the lean there).
     */
    override fun sampleAzimuth(rawTilt: Float, rawOrientation: Float): Float =
        if (panel.isOpen) EbcGeometry.screenAzimuth(panel.isRotated, rawTilt, rawOrientation)
        else 0f

    // ── The direct raster page: painted onto the panel (Phase 28 · Phase 29, 0.1.43) ──
    //
    // What the firmware needle could never do: the lead's own shade, pressure, and no change
    // at pen-up. The mark is [GraphiteGrain]'s flecks — the *same* flecks the bake will lay,
    // from the same call with the same seed — rasterised by the same renderer into a live
    // alpha layer, flattened against the page images exactly as `drawCommittedContent`
    // flattens them, **dithered** ([DitherFlatten]) and written straight into the panel
    // driver's frame 0. At pen-up the stroke bakes into the page image as it always did and
    // the window presents it — dithered the same way, by the same function, at the same page
    // coordinates ([drawRasterLayers]), so the compositor's rewrite lands on the pixels that
    // are already there and the panel does not move. That is the mirror.
    //
    // **Phase 29 gives the whole page the same treatment, and inverts the bake.** The pen
    // previews through a second live layer ([liveInk]) — the new segment each event, round
    // caps, so the seams join exactly — and the rubber shows the corridor it lifts as each
    // batch lands ([onRasterErasedBatch]). And the mark is no longer *re-derived* at pen-up:
    // the live layer **is** the bake ([bakeCapturedStroke]), composited into the page image
    // with the very `SRC_OVER` the live flatten applied to it. No second `GraphiteGrain.of`,
    // no second `drawPoints` — which is both the exactness (the page now holds precisely the
    // pixels the panel showed) and the second of a dense scribble's pen-up that the artist
    // was waiting through.
    //
    // **Why the display is dithered at all** (the third walk, 2026-09-19). This panel's
    // 16-grey waveform reaches black on its first frame and a grey only by passing through
    // black and lightening back out of it over the next second — so a grey pixel trails the
    // nib and a black one does not. The second walk read that as "a shade is a density" and
    // thinned the grain for a pale lead; it landed under the nib cleanly and was the wrong
    // answer, because it changed the *mark*: *"Atelier uses greyscale colours; this just
    // leaves less graphite down, so shade 13 looks like a bug."* Atelier sends the panel
    // nothing but black pixels — that read-back was right — because it **dithers** its grey
    // stroke into an even pattern of them. So the pencil keeps its grey everywhere it is
    // data (the stroke, the page image, covers, exports) and only the glass sees dots.
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
    //  3. **Every live pixel and every displayed pixel go through the same [DitherFlatten]
    //     at the same page coordinates**, so the recompose is a no-op. It was
    //     [RattaPanelTone]'s measured grey → level table that did this job while the panel
    //     was sent greys; now that nothing but black and white reaches it, agreement is a
    //     property of one pure function instead of a table (the table stands — see there).

    /**
     * The alpha of everything this contact has laid — one byte a pixel, the view's size,
     * one array per raster layer, allocated on that layer's first direct contact and reused
     * for the life of the view.
     *
     * A byte array rather than a page-sized ARGB bitmap because only alpha varies: a mark
     * has one colour, and what the renderer varies along it is coverage (a fleck's darkness,
     * a stroke's anti-aliased rim). Keeping the colour out of it means a live layer costs a
     * quarter of a bitmap, and the flatten applies the colour once per pixel instead of
     * storing it a million times.
     *
     * **Two of them since 0.1.43**, because a page has two images and a live mark must be
     * flattened into the one it belongs to — a pencil under a pen must go on reading as
     * graphite under ink, live exactly as baked. Only one is ever non-empty at a time (one
     * contact is one tool), and the ink one is never allocated on a page nothing has been
     * penned on, which is every pencil page there has ever been.
     */
    private var liveGraphite: ByteArray? = null
    private var liveInk: ByteArray? = null
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

    /**
     * How this contact lays graphite — read once at ACTION_DOWN, like every other thing this
     * path latches, because the flecks already on the panel were laid with it and the flatten
     * at pen-up has to agree with them. A shade picked mid-contact is not a thing a hand can
     * do; a shade picked between contacts is, and [beginLivePreview] is where it takes.
     *
     * Since the third walk this is always the core's own answer — the lead's colour,
     * alpha-graded, at full density, anti-aliased — because the panel is handed a dither of
     * the page rather than the pencil's pixels. The seam ([pencilInk]) stays: it is how an
     * engine says how the pencil must be drawn on its glass, and this one no longer needs to
     * say anything.
     */
    private var contactInk: PencilInk = PencilInk(Stroke.BLACK, opaque = false, density = 1f)

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

    /** Which page image this contact's mark belongs to — [RasterLayer.of] the armed style,
     *  latched with everything else at ACTION_DOWN. It decides which live layer is painted
     *  into, which image the flatten reads it over, and which one the bake composites it
     *  into; a mark that changed layers mid-stroke would be half a pencil and half a pen. */
    private var contactLayer: RasterLayer = RasterLayer.GRAPHITE

    /** The colour this contact's ink is laid in — the pen's own, latched like [contactInk]
     *  is for the pencil, and read by the flatten so the panel shows the ink that is
     *  actually going down. */
    private var contactPenColor: Int = Stroke.BLACK

    /**
     * How many of the stroke's points are already in [liveInk].
     *
     * A count rather than an index, so "nothing new has arrived" is one comparison and can
     * never be confused with "the first point has not been drawn yet" — a re-announcement of
     * the same buffer that redrew the same dab would merge its anti-aliased rim into itself
     * and bake a shade darker than it previewed. The next segment starts at the **last**
     * point already drawn, not after it: that shared point is what makes two round-capped
     * segments join exactly where one path would have.
     */
    private var laidInkCount = 0

    /** Where the view sits on screen, read once per contact: the panel's coordinates are
     *  the screen's, and a mid-layout read lies. */
    private val contactScreenLoc = IntArray(2)

    /** Flatten/dither scratch, grown as rects demand: the two page images' pixels over the
     *  rect, the sheet's under them (Phase 46), the batch's own pixels, and the levels
     *  handed to the panel. */
    private var tonePix = IntArray(0)
    private var toneGraphite = IntArray(0)
    private var toneInk = IntArray(0)
    private var toneSheet = IntArray(0)
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
        val mask = ensureLiveLayer(contactLayer) ?: return
        if (contactLayer == RasterLayer.INK) {
            extendLiveInk(points, mask)
            return
        }
        liveEvents++
        val t0 = System.nanoTime()
        // Through the bake's own seams, even though both are identity on this path: a
        // preview that reaches the renderer by a different road is a preview that can drift.
        val baked = bakePoints(points, StrokeStyle.PENCIL)
        val sweep = liveSweep
            ?: GraphiteGrain.begin(penWidth, pendingStrokeSeed(), contactInk.density, pencilLead)
                .also { liveSweep = it }
        val grain = sweep.extend(baked)
        if (grain.count == 0 && laidFlecks == 0) {
            // Not yet decidable (the first ~50 px of arc): show the hand something NOW. A
            // provisional lay of the whole stroke so far — wrong in its first flecks and its
            // end cap, but under the nib — replaced wholesale the moment the sweep starts
            // laying. It is the one place the whole stroke is still swept per event, and it
            // costs nothing: a stroke that short has barely any stations to decide.
            val provisionalGrain = GraphiteGrain.of(
                baked, penWidth, pendingStrokeSeed(), prefix = false,
                density = contactInk.density, lead = pencilLead,
            )
            if (provisionalGrain.count == 0) return
            provisional = true
            clearMaskRect(mask, liveRect)
            val rect = newFleckBounds(provisionalGrain, 0) ?: return
            layFlecks(provisionalGrain, 0, rect, mask)
            val shown = Rect(liveRect); shown.union(rect)
            liveRect.set(shown)
            toneAndPost(shown)
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
            toneAndPost(Rect(liveRect))
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
        drawPencilGrain(canvas, grain, 0, contactInk, penWidth)
        canvas.restoreToCount(save)
        laidFlecks = sweep.count
        mergeBatchIntoLive(scratch, rect, mask)
        liveRect.union(rect)
        toneAndPost(rect)
        val dt = System.nanoTime() - t1
        timeTone += dt
        if (dt > maxTone) maxTone = dt
    }

    /**
     * The pen's live ink (Phase 29): draw the **new segment** into [mask] and show it.
     *
     * Only the segment, never the stroke — a stroke a thousand samples long would otherwise
     * be re-rasterised a thousand times, which is precisely the quadratic the pencil's
     * [GraphiteGrain.Sweep] was written to escape. What makes one segment at a time legal
     * here is the shape of the mark rather than any state: the pen is a uniform round-capped
     * line, so the dab at the end of one segment is the dab at the start of the next and the
     * two join exactly where a single path would have. [laidInkCount] keeps that shared
     * point, which is why the sub-list starts *at* the last point drawn rather than after it.
     */
    private fun extendLiveInk(points: List<StrokePoint>, mask: ByteArray) {
        if (points.isEmpty()) return
        // Nothing new: the base may re-announce the same buffer (every sample of a batch
        // dropped inside an exclusion rect leaves the list unchanged).
        if (points.size <= laidInkCount) return
        liveEvents++
        val t0 = System.nanoTime()
        val from = if (laidInkCount == 0) 0 else laidInkCount - 1
        val segment = points.subList(from, points.size)
        val rect = inkBounds(segment) ?: run { laidInkCount = points.size; return }
        val scratch = ensureBatch(rect.width(), rect.height()) ?: return
        val canvas = batchCanvas ?: return
        val save = canvas.save()
        canvas.clipRect(0, 0, rect.width(), rect.height())
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
        drawPenInk(canvas, segment, contactPenColor, penWidth)
        canvas.restoreToCount(save)
        laidInkCount = points.size
        mergeBatchIntoLive(scratch, rect, mask)
        liveRect.union(rect)
        toneAndPost(rect)
        val dt = System.nanoTime() - t0
        timeTone += dt
        if (dt > maxTone) maxTone = dt
    }

    /** The view-space rect a run of pen samples covers: the points' bounds pushed out by
     *  half the lead's width for the round cap, plus two px for the anti-aliased rim and
     *  the rounding out. Null when none of it is on screen. */
    private fun inkBounds(points: List<StrokePoint>): Rect? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        if (minX > maxX) return null
        val pad = ceil(penWidth / 2f).toInt() + 2
        toneRect.set(
            floor(minX).toInt() - pad,
            floor(minY).toInt() - pad,
            ceil(maxX).toInt() + pad,
            ceil(maxY).toInt() + pad,
        )
        return if (toneRect.intersect(0, 0, liveAlphaW, liveAlphaH)) toneRect else null
    }

    /** Rasterise flecks `[from, count)` into the batch scratch and merge them into [mask]. */
    private fun layFlecks(grain: GraphiteGrain.Grain, from: Int, rect: Rect, mask: ByteArray) {
        val scratch = ensureBatch(rect.width(), rect.height()) ?: return
        val canvas = batchCanvas ?: return
        val save = canvas.save()
        canvas.clipRect(0, 0, rect.width(), rect.height())
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
        drawPencilGrain(canvas, grain, from, contactInk, penWidth)
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
     * Flatten [rect] the way the page will be seen, dither it, and show it: white paper, the
     * graphite image with this contact's flecks over it, and the ink image with this
     * contact's ink over *it*, ink on top (`SRC_OVER`, 0.1.44) — the same order and the
     * same operator `drawCommittedContent` uses, because anything else would be a second
     * opinion about what the page looks like and pen-up would be where the two met.
     *
     * **Both live layers, every time** (0.1.43). Only one of them is ever non-empty — a
     * contact is one tool — but which one it is is the contact's business and not this
     * function's, and a flatten that had to be told would be a flatten that could be told
     * wrong. A layer with no array at all costs one null check.
     *
     * Every pixel leaves as black (level 0) or white (level 15) and nothing in between:
     * both land on the panel's first frame, so the mark is under the nib rather than a beat
     * behind it, and the whole-page dither the window presents at pen-up
     * ([regenDither]) is the same arithmetic at the same page coordinates, so it agrees
     * pixel for pixel. **A settled mark is painted in tone** (Phases 31 and 34,
     * [DitherFlatten.coverage]): a pixel either image covers, with no live layer on it and
     * outside the runs still waiting ([pendingRuns], Phase 32), goes to the panel at its
     * grey's own level ([LEVEL_OF_COVERAGE], [RattaPanelTone]'s table back on this path for
     * exactly that); the window's own frame agrees.
     */
    private fun toneAndPost(rect: Rect, settled: Boolean = DISPLAY_SETTLES && !overlapsPending(rect)) {
        val w = rect.width()
        val h = rect.height()
        val n = w * h
        if (n <= 0) return
        ensureToneScratch(n)
        readRaster(RasterLayer.GRAPHITE, rect, toneGraphite)
        readRaster(RasterLayer.INK, rect, toneInk)
        readSheet(rect, toneSheet)
        // A live layer is read only where the rect is certainly inside it. An inking
        // contact's rects always are (both bounds calls clip to the layer); a rubbing
        // sweep's come from the *page*, which may be larger than the view, and during one
        // the live layers are empty anyway.
        val onLayer = rect.left >= 0 && rect.top >= 0 &&
            rect.right <= liveAlphaW && rect.bottom <= liveAlphaH
        val graphiteMask = if (onLayer) liveGraphite else null
        val inkMask = if (onLayer) liveInk else null
        // The colours the two live layers were laid in: the lead's own, and the pen's. The
        // dither is what turns either of them into dots.
        val leadColor = contactInk.color
        val penInkColor = contactPenColor
        for (y in 0 until h) {
            val maskRow = (rect.top + y) * liveAlphaW + rect.left
            val row = y * w
            val pageY = rect.top + y
            for (x in 0 until w) {
                val coverage = DitherFlatten.coverage(
                    toneGraphite[row + x],
                    if (graphiteMask == null) 0 else graphiteMask[maskRow + x].toInt() and 0xFF,
                    leadColor,
                    toneInk[row + x],
                    if (inkMask == null) 0 else inkMask[maskRow + x].toInt() and 0xFF,
                    penInkColor,
                    rect.left + x,
                    pageY,
                    settled,
                    toneSheet[row + x],
                )
                toneLevels[row + x] = LEVEL_OF_COVERAGE[coverage]
            }
        }
        postLevels(rect, toneLevels, w)
    }

    /**
     * Put [levels] — [rect]'s pixels, [stride] to a row, in view coordinates — on the
     * panel, **cut around the host's exclusion rects** ([PanelClip]). The daemon never
     * painted inside a chrome zone; neither may this view, or a segment drawn up to a bar
     * writes page pixels over the bar until the compositor next repaints it. Each piece
     * goes out on its own with the same array, indexed from the rect's corner.
     */
    private fun postLevels(rect: Rect, levels: ByteArray, stride: Int) {
        val loc = contactScreenLoc
        if (exclusionQuads.isEmpty()) {
            toneScreenRect.set(rect)
            toneScreenRect.offset(loc[0], loc[1])
            panel.post(toneScreenRect, levels, stride)
            return
        }
        clipQuad[0] = rect.left; clipQuad[1] = rect.top; clipQuad[2] = rect.right; clipQuad[3] = rect.bottom
        val pieces = PanelClip.subtract(clipQuad, exclusionQuads)
        var i = 0
        while (i + 3 < pieces.size) {
            toneScreenRect.set(pieces[i] + loc[0], pieces[i + 1] + loc[1], pieces[i + 2] + loc[0], pieces[i + 3] + loc[1])
            panel.post(toneScreenRect, levels, stride, rect.left + loc[0], rect.top + loc[1])
            i += 4
        }
    }

    private val clipQuad = IntArray(4)

    /**
     * [layer]'s pixels for [rect] into [into], zero-filled where the page image does not
     * reach (a page smaller than the view, or a layer nothing has landed on). False when
     * there is no image at all — the common case for ink on a pencil page, and worth the
     * early return: it is a page-sized bitmap that never gets allocated. The array is
     * zeroed either way, so a caller may read it without asking.
     */
    private fun readRaster(layer: RasterLayer, rect: Rect, into: IntArray): Boolean =
        readPixels(flattenBase(layer), rect, into)

    /** [readRaster] for the sheet under a raster page (Phase 46) — through [sheetFor], never
     *  [flattenBase], so a direct stroke page never reads one. */
    private fun readSheet(rect: Rect, into: IntArray): Boolean = readPixels(sheetFor(), rect, into)

    /** [readRaster]'s body for any image — zero-filled first, false when there is none. */
    private fun readPixels(bitmap: Bitmap?, rect: Rect, into: IntArray): Boolean {
        val w = rect.width()
        val h = rect.height()
        java.util.Arrays.fill(into, 0, w * h, 0)
        if (bitmap == null) return false
        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val right = rect.right.coerceAtMost(bitmap.width)
        val bottom = rect.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return false
        val offset = (top - rect.top) * w + (left - rect.left)
        bitmap.getPixels(into, offset, w, left, top, right - left, bottom - top)
        return true
    }

    /**
     * [readRaster] for the band kernel: the same pixels, but a layer with no image at all
     * is answered with `false` and [into] is **not touched**.
     *
     * The zero-fill [readRaster] does is a kindness to a caller that reads the array
     * without asking; here it is a page-sized write of nothing on every rebuild, for the
     * ink layer of every pencil page there has ever been. [DitherFlatten.band] takes the
     * flag instead. The fill stays for a layer whose image does not cover the whole band —
     * a page smaller than the view — because those pixels really are blank paper.
     */
    private fun readRasterBand(layer: RasterLayer, rect: Rect, into: IntArray): Boolean =
        readPixelsBand(flattenBase(layer), rect, into)

    /** [readRasterBand] for the sheet (Phase 46) — through [sheetFor], as [readSheet]. */
    private fun readSheetBand(rect: Rect, into: IntArray): Boolean =
        readPixelsBand(sheetFor(), rect, into)

    /** [readRasterBand]'s body for any image — [into] untouched when there is none. */
    private fun readPixelsBand(bitmap: Bitmap?, rect: Rect, into: IntArray): Boolean {
        if (bitmap == null) return false
        val w = rect.width()
        val h = rect.height()
        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val right = rect.right.coerceAtMost(bitmap.width)
        val bottom = rect.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return false
        val covers = left == rect.left && top == rect.top &&
            right == rect.right && bottom == rect.bottom
        if (!covers) java.util.Arrays.fill(into, 0, w * h, 0)
        val offset = (top - rect.top) * w + (left - rect.left)
        bitmap.getPixels(into, offset, w, left, top, right - left, bottom - top)
        return true
    }

    private fun ensureToneScratch(n: Int) {
        if (tonePix.size < n) tonePix = IntArray(n)
        if (toneGraphite.size < n) toneGraphite = IntArray(n)
        if (toneInk.size < n) toneInk = IntArray(n)
        if (toneSheet.size < n) toneSheet = IntArray(n)
        if (toneLevels.size < n) toneLevels = ByteArray(n)
    }

    /**
     * [layer]'s live alpha layer, sized to the view — allocated on that layer's first direct
     * contact and kept for the life of the view. Null before layout, which is when nothing
     * can be drawn anyway.
     *
     * A resize drops **both**, because they are indexed by one width and a stale one would
     * be read at the wrong offsets; the contact that asked gets a fresh, blank layer, which
     * is the right answer for a view whose geometry just changed under it.
     */
    private fun ensureLiveLayer(layer: RasterLayer): ByteArray? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        if (liveAlphaW != w || liveAlphaH != h) {
            liveGraphite = null
            liveInk = null
            liveAlphaW = w
            liveAlphaH = h
        }
        return when (layer) {
            RasterLayer.GRAPHITE -> liveGraphite ?: ByteArray(w * h).also { liveGraphite = it }
            RasterLayer.INK -> liveInk ?: ByteArray(w * h).also { liveInk = it }
        }
    }

    /** [layer]'s live layer as it stands, or null when nothing has been laid on it. */
    private fun liveLayer(layer: RasterLayer): ByteArray? =
        if (layer == RasterLayer.GRAPHITE) liveGraphite else liveInk

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

    // ── The dithered display: what the WINDOW shows while the panel is ours ──
    //
    // The live half above paints the panel under the nib. This half is what the window
    // presents afterwards, and the two must be the same picture — so the page is drawn to
    // the glass as exactly the flatten [toneAndPost] sends, through exactly the same
    // [DitherFlatten] at exactly the same page coordinates: a blue-noise dither of any
    // mark still waiting, and — Phases 31 and 34 — every settled mark in its **true tone**
    // ([DitherFlatten.coverage]). Then the compositor's rewrite of frame 0 is a no-op on
    // every pixel and there is nothing to see at pen-up.
    //
    // It applies to the whole raster page while the panel is open: the window shows one
    // page, and the rule is per pixel, not per armed tool, so nothing changes appearance
    // at a tool push. Covers and exports are never dithered — that is what `forDisplay`
    // is for.

    /**
     * The page as black coverage, one byte a pixel — dots for a mark still waiting, tone
     * for a settled one (Phases 31–34) — an `ALPHA_8` bitmap because the only
     * thing being said about a pixel is whether it is inked, and a black [android.graphics.Paint]
     * at draw time supplies the colour. A quarter of the memory of the page it mirrors.
     *
     * Allocated on first use and **dropped rather than cleared** when the page goes (see
     * [onRasterPixelsChanged]): the committed display list holds its own reference to the
     * bitmap it was recorded with, exactly as it does for the page images, and blanking one
     * in place would wipe the panel a frame before the next page lands.
     */
    private var ditherDisplay: Bitmap? = null
    private var ditherW = 0
    private var ditherH = 0

    /** Dither scratch: one band's worth of each page image, and of the `Int` alpha the
     *  small-rect path hands `setPixels`. */
    private var bandGraphite = IntArray(0)
    private var bandInk = IntArray(0)
    private var bandSheet = IntArray(0)
    private var bandOut = IntArray(0)
    private val bandRect = Rect()

    /**
     * **The page's dither as bytes — the truth, and [ditherDisplay] is its copy.**
     *
     * Every rebuild, whole page or rect, flattens into these rows first (the bitmap's own
     * layout: `rowBytes` to a row, padding and all), and only then lands them in the
     * bitmap — by one `copyPixelsFromBuffer` of the whole thing where the rect is a large
     * share of the page, or by a `setPixels` of just the rect where it is not
     * ([DitherCost]). That ordering is what makes the whole-bitmap copy legal after a
     * rect rebuild: the array is never behind the bitmap, so copying all of it can never
     * undo a rect somebody else wrote.
     *
     * Lives and dies with [ditherDisplay] — allocated with it in [ensureDither] and
     * dropped with it in [dropDither], because an array left over from the previous page
     * would be landed whole onto a fresh bitmap the first time a large rect arrived.
     */
    private var ditherBytes: ByteArray? = null
    private var ditherBuffer: java.nio.ByteBuffer? = null

    /** The view's levels for [presentPageViaPanel] — [toneLevels]'s whole-page twin, grown
     *  once and kept, because the page it describes is the same size every turn. */
    private var pageLevels = ByteArray(0)
    private val pageScreenRect = Rect()

    /** Which whole-page rebuilds are pending, and which redraws wait for them. */
    private val ditherCoalescer = DitherCoalescer()

    /** Whether the window is showing the dither rather than the page images themselves. */
    private val ditherDisplayed: Boolean
        get() = panel.isOpen && pageMode == PageMode.RASTER

    // ── The committed picture as the flatten base (Phase 42) ─────────────────
    //
    // A stroke page has no page images to flatten against, which is the one reason it was
    // left to the daemon through Phases 28–41. So on a direct stroke page the base is the
    // **committed picture** — white, the template, the host's content, the strokes — drawn
    // by `drawCommittedContent` itself into an image of the view's size, and everything
    // above reads it where it read the graphite image: the live flatten under the nib
    // ([toneAndPost]), the display dither ([ditherBand]), a rect presented from the
    // display bytes. Ink over it composites with the same `srcOver` a pen's ink composites
    // over graphite; the ink image is simply absent.
    //
    // It is kept current the way the page images are — by the change, not by a redraw:
    // a mark composites its live layer in over its runs, an erased or undone stroke
    // re-renders its rect from the vector, and anything without a rect re-renders the
    // whole page in the deferred rebuild. The window's record is the dither of it, so the
    // compositor's rewrite of frame 0 lands on the pixels already there — the raster
    // page's mirror, on a page that keeps its strokes.

    /** The committed picture, view-sized, ARGB — allocated on the first direct stroke
     *  render, dropped with the page, the panel or the opt-out. About 10 MB on a Nomad
     *  page: the cost of a flatten base a stroke page does not otherwise have. */
    private var committedPage: Bitmap? = null
    private var committedCanvas: Canvas? = null

    /** Whether [committedPage] is current when a redraw arrives — see [CommittedCache]. */
    private val committedCache = CommittedCache()

    /** [committedPage] at the view's size, made fresh when the view changes shape. Null
     *  before layout. */
    private fun ensureCommittedPage(): Bitmap? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val existing = committedPage
        if (existing != null && existing.width == w && existing.height == h) return existing
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        committedPage = bitmap
        committedCanvas = Canvas(bitmap)
        // A fresh image is blank everywhere a caller's rect is not: whatever that caller
        // marks, the next redraw must render the whole page.
        committedCache.invalidate()
        return bitmap
    }

    private fun dropCommittedPage() {
        committedPage = null
        committedCanvas = null
    }

    /**
     * Render the committed picture into [committedPage] over [rect], or the whole of it
     * when null — the base's own `drawCommittedContent`, true greys, clipped. The one
     * place the vector page is drawn on this path; every other update of the image is a
     * composite of a live layer ([bakeAfterCommit]).
     */
    private fun renderCommittedPage(rect: Rect?) {
        if (ensureCommittedPage() == null) return
        val canvas = committedCanvas ?: return
        val t0 = System.nanoTime()
        val save = canvas.save()
        if (rect != null) canvas.clipRect(rect)
        super.drawCommittedContent(canvas, forDisplay = false)
        canvas.restoreToCount(save)
        if (rect == null) {
            Log.i(TAG, "committed: whole page ${width}x$height rendered in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /**
     * What [layer] is flattened over: the page image on a raster page; on a stroke page
     * the committed picture stands where the graphite image would, and there is no ink
     * image. Keyed on the mode rather than on [directStroke], so a read mid-flip is
     * consistent with itself.
     */
    private fun flattenBase(layer: RasterLayer): Bitmap? =
        if (pageMode == PageMode.STROKE) {
            if (layer == RasterLayer.GRAPHITE) committedPage else null
        } else {
            rasterFor(layer)
        }

    /** The flatten's extent: the page images' on a raster page, the view's on a stroke
     *  page (the committed picture is drawn to the view, template and all). */
    private val flattenW: Int get() = if (pageMode == PageMode.STROKE) width else rasterPageWidth
    private val flattenH: Int get() = if (pageMode == PageMode.STROKE) height else rasterPageHeight

    /**
     * The window's picture of a direct stroke page is the dither of the committed image
     * ([ditherDisplay]), not the vector content — so that what the compositor rewrites
     * into frame 0 is what this view already painted there. Only for the window
     * (`forDisplay`), and only once a rebuild has made the dither: before that, and for a
     * cover or an export, the page draws as it is stored.
     */
    override fun drawCommittedContent(canvas: Canvas, forDisplay: Boolean) {
        if (forDisplay && directStroke && committedPage != null) {
            val dither = ditherDisplay
            if (dither != null) {
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(dither, 0f, 0f, ditherPaint)
                return
            }
        }
        super.drawCommittedContent(canvas, forDisplay)
    }

    /**
     * Strokes were added to or removed from the committed model over [rect] and a redraw
     * is about to follow (Phase 42): re-render that rect of the committed image from the
     * vector, dither it, and mark the image current so the redraw only mirrors. An erase
     * batch is the one caller whose redraw does **not** follow at once — the cadence is
     * end-only here — so its rect goes to the panel now, under the tip: the user's
     * decision 8, an erased stroke vanishes as the tip crosses it.
     */
    override fun onCommittedStrokesChanged(rect: Rect) {
        if (!firmware || !directStroke) return
        if (ensureCommittedPage() == null) return
        renderCommittedPage(rect)
        regenDither(rect)
        committedCache.markCurrent()
        if (contactErasing) postRectFromDither(rect)
    }

    override fun notifyContentChanged() {
        // The host's content moved and nothing laid it: whatever else was marked, the next
        // redraw rebuilds the whole page.
        committedCache.invalidate()
        super.notifyContentChanged()
    }

    /**
     * Draw the raster page for [forDisplay].
     *
     * On the glass, with the panel ours, that is the display image and **not** the page
     * images: a waiting mark as black-or-white dots and a settled one in its tone, which
     * is what the panel was painted with under the nib and after the settle. Anywhere else — a cover, an export, the host's own data — it is the
     * base's two blits and the artist's true greys. The dither is drawn with a black paint
     * because an `ALPHA_8` bitmap takes its colour from the paint.
     *
     * **The sheet ([setSheet], Phase 46) needs no path of its own here**: on the glass it is
     * a third input to the same dither, so it shows dithered with the page; elsewhere the
     * base draws it for display and never for an export. The dither's zero byte still lets
     * the template show through in the window while the panel shows [LEVEL_WHITE] there —
     * the template is not flattened with the page, the sheet is (the gap is unchanged).
     */
    override fun drawRasterLayers(canvas: Canvas, forDisplay: Boolean) {
        if (!forDisplay || !ditherDisplayed) {
            super.drawRasterLayers(canvas, forDisplay)
            return
        }
        ditherDisplay?.let { canvas.drawBitmap(it, 0f, 0f, ditherPaint) }
    }

    /** Black, aliased, no filtering — the ink an `ALPHA_8` page is drawn in. */
    private val ditherPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
        isFilterBitmap = false
    }

    /**
     * The page's pixels moved: bring the dither up to date over the same ground, before the
     * caller presents anything.
     *
     * A null rect is the whole page — a load, a clear, a content swap. When it is the page
     * *going* (both images dropped) the bitmap is let go rather than cleared, so a content
     * swap holds the old pixels on the panel until the new page lands, which is the same
     * courtesy the base extends to the page images themselves.
     *
     * **A sheet keeps a page from being gone** (Phase 46): a sheet set on a blank page is
     * still a page to show, so it rebuilds like a load. Except inside a content swap
     * ([swappingContent]), where the page is going whatever lies under it — the old pixels
     * hold on the panel until the new page lands, sheet or no sheet.
     */
    override fun onRasterPixelsChanged(rect: Rect?) {
        if (!ditherDisplayed) return
        // A page load has nothing waiting: the page is rebuilt settled (Phase 32). Any other
        // change that is not the pen's own bake — a rub, an undo — leaves what is waiting
        // waiting (Phase 37): only the host's ask settles now.
        if (rect == null) pendingRuns.clear()
        if (rect == null) {
            val gone = rasterFor(RasterLayer.GRAPHITE) == null &&
                rasterFor(RasterLayer.INK) == null &&
                (swappingContent || sheetFor() == null)
            if (gone) {
                ditherCoalescer.onPageGone()
                removeCallbacks(ditherRebuild)
                dropDither()
                return
            }
            // Deferred, and coalesced: a two-raster page is two of these calls back to
            // back, and rebuilding on each is half a second of a Nomad's time thrown away
            // plus a frame showing graphite with no ink. See [DitherCoalescer].
            if (ditherCoalescer.onWholePage() == DitherCoalescer.Action.SCHEDULE_WHOLE) {
                post(ditherRebuild)
            }
            return
        }
        // A rect stays synchronous: it precedes a present that is already on its way, and
        // one deferred would be a mark that appears a frame late.
        if (ditherCoalescer.onRect() == DitherCoalescer.Action.REBUILD_RECT) {
            regenDither(rect)
        }
    }

    /**
     * One mark's runs, as one piece of news — **and one landing** (2026-09-19).
     *
     * The base would forward these to the single-rect form one at a time, which is what a
     * large pencil scribble was doing: up to sixty-four `setPixels` calls, none of them
     * slow enough to log, summing to most of a **651 ms** pen-up on a Nomad. Through the
     * coalescer exactly as a single rect is — a pending whole-page rebuild subsumes the
     * whole batch, because it will cover every run in it.
     */
    override fun onRasterPixelsChanged(rects: List<Rect>) {
        if (!ditherDisplayed || rects.isEmpty()) return
        if (ditherCoalescer.onRect() == DitherCoalescer.Action.REBUILD_RECT) {
            regenDitherRuns(rects)
        }
    }

    // ── Marks waiting to settle (Phase 32; the pencil too since Phase 34) ───────
    //
    // A mark is baked at pen-up but stays on the glass as the dither it was drawn as; it
    // goes to its true tone when the host asks ([settleDisplay]) or when the page is
    // reloaded — and at nothing else (Phase 37, 2026-09-21: *"only … the swipe gesture,
    // page flip, or closing the sketch. No longer on tool change."*). Phases 32–35 settled
    // at every non-drawing event the engine saw — a tool or pen-property change, a rub, an
    // undo, a 2.5 s pause — and each of those landed a settle the hand had not asked for.
    // The runs waiting are kept here; the display rule dithers inside them and tones
    // outside them ([toneAndPost]'s and [flattenDither]'s `settled`).

    /** The host's door (0.1.47) — since Phase 37 the **only** settle besides a page load; since
     *  Phase 41 a no-op, because nothing on this panel settles any more ([DISPLAY_SETTLES]). */
    override fun settleDisplay() { if (DISPLAY_SETTLES) settleInkTone(panel = true) }

    /** The runs baked since the last settle, either layer — in page coordinates. */
    private val pendingRuns = ArrayList<Rect>()

    // No settle on a timer (Phase 37, 0.1.52). Phase 35's pause settle — 2.5 s with no new
    // contact — is withdrawn on the user's word: a settle the hand did not ask for landed
    // where the hand was about to draw, and its side effects outweighed the convenience. A
    // mark now waits until something that is not a mark happens, or until the host asks
    // through [settleDisplay] — which the sketch face binds to a one-finger swipe down.

    private fun overlapsPending(rect: Rect): Boolean {
        for (r in pendingRuns) if (Rect.intersects(r, rect)) return true
        return false
    }

    /**
     * Show every waiting run in its true tone — in the window's display image and, with
     * the panel ours and no contact down, on the panel itself — and forget them.
     * Idempotent and cheap when nothing waits, which is the common case, so every caller
     * asks without checking.
     */
    private fun settleInkTone(panel: Boolean = true) {
        if (pendingRuns.isEmpty()) return
        val runs = ArrayList(pendingRuns)
        pendingRuns.clear()
        if (!ditherDisplayed) return
        val t0 = System.nanoTime()
        regenDitherRuns(runs)
        redrawCommitted()
        // A settle a property setter triggered goes through the window only: a tap on a
        // button is what usually set the property, and that button's own chrome — or a bar
        // it just opened — may be over the page, which a panel post would paint over. The
        // compositor carries the window's frame to the panel a beat later. The host's own
        // [settleDisplay] and a raster change post straight to the panel: the host calls
        // before its chrome opens, and a rub or an undo has nothing over the page.
        //
        // **One post, not one per run** (Phase 37's second walk — "a multi-pass repaint
        // section by section"). A mark's bake leaves up to sixty-four runs, and a page of
        // drawing hundreds; posted one at a time each was its own panel write and its own
        // refresh, and the settle read as the page repainting itself in pieces. The runs'
        // union goes to the panel in a single write out of the display bytes [regenDitherRuns]
        // has just rebuilt — the same bytes the window is about to present, so the two agree.
        if (panel && directRaster && !contactDirect && !contactRubbing) {
            toneRect.setEmpty()
            for (r in runs) toneRect.union(r)
            if (toneRect.intersect(0, 0, width, height)) presentRectViaPanel(toneRect)
        }
        Log.i(TAG, "settled: ${runs.size} run(s) in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    /** The posted whole-page rebuild: build it once, show it on the panel, then present
     *  it once. */
    private val ditherRebuild = Runnable {
        if (!ditherCoalescer.takeScheduled()) return@Runnable
        // A change laid while this rebuild was pending was consumed by it, not by a
        // redraw of its own: a mark left standing here would let the next unrelated
        // redraw mirror a page it should have rebuilt.
        committedCache.take()
        val stroke = directStroke
        if (!ditherDisplayed && !stroke) return@Runnable
        // A stroke page's base is rendered here, once, before the dither reads it.
        if (stroke) renderCommittedPage(null)
        regenDither(null)
        presentPageViaPanel()
        if (stroke) {
            // Straight to the record: redrawCommitted would schedule another rebuild.
            if (recordCommitted()) invalidate()
        } else {
            redrawCommitted()
        }
    }

    /**
     * Put the page the rebuild just made on the glass **ourselves**, before the window
     * presents the same pixels — see [PRESENT_LOADED_PAGE_VIA_PANEL] for why.
     *
     * The whole view rect, in the panel's two levels, straight out of [ditherBytes]: that
     * array is the page's truth and has just been rebuilt, so no flatten is repeated here
     * and nothing is read out of a bitmap. Where the page does not reach the view — a page
     * smaller than the glass — the levels are white, which is the paper the base draws
     * there.
     *
     * **Whole-page rebuilds only.** A rect rebuild is a mark or a rub landing, and both of
     * those have already posted their own rect under the nib ([toneAndPost]); posting the
     * page again behind them would be a page-sized write per mark.
     *
     * **Never while a contact is down.** The compositor's frame is the one thing that can
     * be behind the hand, so if a direct or rubbing contact is live this stands aside and
     * lets the frame it has already posted stand. The screen offset is read here for the
     * same reason [beginLivePreview] reads it — the panel speaks screen coordinates — and
     * into the same field, which no live contact is using.
     */
    private fun presentPageViaPanel() {
        if (!PRESENT_LOADED_PAGE_VIA_PANEL) return
        if (width <= 0 || height <= 0) return
        pageScreenRect.set(0, 0, width, height)
        presentRectViaPanel(pageScreenRect)
    }

    /**
     * [presentPageViaPanel]'s body for any view rect (Phase 37): [rect]'s levels out of
     * [ditherBytes] — the display's truth, already flattened — in one panel write. White
     * where the page image does not reach. Never while a contact is down. [rect] is in
     * view coordinates and is not kept.
     */
    private fun presentRectViaPanel(rect: Rect) {
        if (!direct) return
        if (contactDirect || contactRubbing) return
        postRectFromDither(rect)
    }

    /**
     * [presentRectViaPanel] without its contact guard — for a caller that *is* the contact:
     * an erase batch on a direct stroke page, and the trail's wipe at pen-up, both of which
     * want the committed picture on the glass now. Reads the screen offset afresh only
     * when no contact is down; a contact took it at ACTION_DOWN, and a mid-layout read lies.
     */
    private fun postRectFromDither(rect: Rect) {
        if (!direct) return
        val bitmap = ditherDisplay ?: return
        val bytes = ditherBytes ?: return
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0) return
        val t0 = System.nanoTime()
        val n = w * h
        if (pageLevels.size < n) pageLevels = ByteArray(n)
        val stride = bitmap.rowBytes
        val pageW = (minOf(rect.right, bitmap.width) - rect.left).coerceAtLeast(0)
        val pageH = (minOf(rect.bottom, bitmap.height) - rect.top).coerceAtLeast(0)
        for (y in 0 until h) {
            val row = y * w
            if (y >= pageH) {
                java.util.Arrays.fill(pageLevels, row, row + w, LEVEL_WHITE)
                continue
            }
            val src = (rect.top + y) * stride + rect.left
            for (x in 0 until pageW) {
                pageLevels[row + x] = LEVEL_OF_COVERAGE[bytes[src + x].toInt() and 0xFF]
            }
            if (pageW < w) java.util.Arrays.fill(pageLevels, row + pageW, row + w, LEVEL_WHITE)
        }
        if (!isPenDown) getLocationOnScreen(contactScreenLoc)
        postLevels(rect, pageLevels, w)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "panel: ${w}x$h presented in $ms ms")
    }

    /**
     * Rebuild the dither over [rect], or over the whole page when it is null.
     *
     * In horizontal bands, so the scratch a page-wide rebuild needs is a megabyte rather
     * than the page's own four: a band at a time is also how the cost stays predictable on
     * the one call that has a deadline — a page turn, where the artist is waiting.
     *
     * **Two costs, and the rect is now the one that bites** (2026-09-19). The whole page
     * was 494–551 ms on a Nomad and two of them per page open; the per-pixel work is
     * [DitherFlatten.band]'s since, and the rows land in one `copyPixelsFromBuffer`
     * rather than two and a half million `Int`s through `setPixels`. What that left
     * behind was a *rect* path several times dearer per pixel than the whole-page one —
     * invisible while the rects were a rubbed corridor's, and **848 ms on the pen-up of
     * one pencil stroke** once a mark announced its bounding box (measured through
     * NSE · Sketch; the core seam now announces per run, which is the other half of this
     * fix). So the two paths are chosen between rather than assigned: every rebuild
     * flattens into [ditherBytes] — the page's own rows, always the truth — and a rect
     * from [DITHER_WHOLE_COPY_FRACTION] of the page upward lands through the same
     * whole-bitmap copy the page uses, because past that share one memcpy beats the
     * expansion ([DitherCost]). Below it, `setPixels` of just the rect, since `ALPHA_8`
     * has no sub-rect byte entry and a short mark's bounds is not worth a page-wide copy.
     *
     * **The log line is what a walk judges this by**: the whole page always, a rect when
     * it cost 20 ms or more. A page turn's target is under 100 ms; a pen-up should not be
     * visible at all.
     */
    private fun regenDither(rect: Rect?) {
        val bitmap = ensureDither() ?: return
        val full = Rect(0, 0, bitmap.width, bitmap.height)
        val area = Rect(rect ?: full)
        if (!area.intersect(full) || area.isEmpty) return
        val t0 = System.nanoTime()
        val whole = area == full
        val stride = bitmap.rowBytes
        val out = ditherBytes ?: return
        // Large enough that one memcpy of the whole page beats expanding this rect into
        // `Int`s for Skia? The page itself always is.
        val wholeCopy = DitherCost.preferWholeCopy(
            area.width(), area.height(), bitmap.width, bitmap.height, DITHER_WHOLE_COPY_FRACTION,
        )
        flattenDither(area, out, stride, settled = DISPLAY_SETTLES && !overlapsPending(area))
        if (wholeCopy) landWholeDither(bitmap) else landDitherRect(bitmap, area, out, stride)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (whole) {
            Log.i(TAG, "dither: whole page ${bitmap.width}x${bitmap.height} in $ms ms")
        } else if (ms >= DITHER_SLOW_RECT_MS) {
            Log.i(TAG, "dither: rect $area in $ms ms (${if (wholeCopy) "page copy" else "setPixels"})")
        }
    }

    /**
     * Rebuild the dither over one mark's **runs**, landing them once.
     *
     * The flatten is per run, so a mark pays for its ink and never for the white space a
     * diagonal spans — that is 0.1.33's rule and the reason the seam carries runs at all.
     * The *landing* is the thing that must not be paid sixty-four times: `setPixels` has a
     * cost per call that a small rect does not amortise, and a scribble filling the middle
     * of the page spent **651 ms** of pen-up on a Nomad in exactly that, with not one rect
     * slow enough to reach the log line. So the runs go into [ditherBytes] one at a time
     * and reach the bitmap together — one `copyPixelsFromBuffer` of the page when the
     * union is large or the runs are many, a `setPixels` each when they are neither
     * ([DitherCost.preferWholeCopyForRuns]).
     *
     * The whole-page copy is legal here for the same reason it is in [regenDither]:
     * [ditherBytes] is the page's truth and is never behind the bitmap.
     */
    private fun regenDitherRuns(rects: List<Rect>) {
        val bitmap = ensureDither() ?: return
        val full = Rect(0, 0, bitmap.width, bitmap.height)
        val stride = bitmap.rowBytes
        val out = ditherBytes ?: return
        val t0 = System.nanoTime()
        val areas = ArrayList<Rect>(rects.size)
        var union: Rect? = null
        for (r in rects) {
            val area = Rect(r)
            if (!area.intersect(full) || area.isEmpty) continue
            areas.add(area)
            union = union?.apply { union(area) } ?: Rect(area)
        }
        val span = union ?: return
        for (area in areas) flattenDither(area, out, stride, settled = DISPLAY_SETTLES && !overlapsPending(area))
        val wholeCopy = DitherCost.preferWholeCopyForRuns(
            areas.size, span.width(), span.height(), bitmap.width, bitmap.height,
            DITHER_WHOLE_COPY_FRACTION, DITHER_MAX_SETPIXELS_RECTS,
        )
        if (wholeCopy) {
            landWholeDither(bitmap)
        } else {
            for (area in areas) landDitherRect(bitmap, area, out, stride)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms >= DITHER_SLOW_RECT_MS) {
            Log.i(
                TAG,
                "dither: ${areas.size} runs, union ${span.width()}x${span.height()} in $ms ms " +
                    "(${if (wholeCopy) "page copy" else "setPixels"})",
            )
        }
    }

    /**
     * Flatten [area] into [out] — the page's own rows, [stride] bytes each — in horizontal
     * bands, so the scratch a page-wide rebuild needs is a megabyte rather than the page's
     * own four. Nothing reaches the bitmap here; the array is the truth and the landing is
     * a separate decision.
     */
    private fun flattenDither(area: Rect, out: ByteArray, stride: Int, settled: Boolean = DISPLAY_SETTLES) {
        val w = area.width()
        val bandH = (DITHER_BAND_PX / w).coerceIn(1, area.height())
        ensureBandScratch(w * bandH)
        var top = area.top
        while (top < area.bottom) {
            val bottom = minOf(top + bandH, area.bottom)
            ditherBand(area, top, bottom, out, top * stride + area.left, stride, settled)
            top = bottom
        }
    }

    /** Land [area]'s already-flattened bytes in the bitmap through `setPixels`, banded —
     *  `ALPHA_8` has no sub-rect byte entry, so each row is expanded into `Int`s for Skia
     *  to take one byte in four back out of. Cheap for a small rect, which is the only
     *  kind that comes here. */
    private fun landDitherRect(bitmap: Bitmap, area: Rect, out: ByteArray, stride: Int) {
        val w = area.width()
        val bandH = (DITHER_BAND_PX / w).coerceIn(1, area.height())
        ensureBandScratch(w * bandH)
        var top = area.top
        while (top < area.bottom) {
            val bottom = minOf(top + bandH, area.bottom)
            var i = 0
            for (y in top until bottom) {
                var src = y * stride + area.left
                for (x in 0 until w) {
                    // The byte is the alpha: `ALPHA_8` reads one byte in four back out.
                    bandOut[i++] = (out[src++].toInt() and 0xFF) shl 24
                }
            }
            bitmap.setPixels(bandOut, 0, w, area.left, top, w, bottom - top)
            top = bottom
        }
    }

    /** Land the whole page in one memcpy — [ditherBytes] is the bitmap's own layout,
     *  padding and all, so this can never undo a rect somebody else wrote. */
    private fun landWholeDither(bitmap: Bitmap) {
        val buffer = ditherBuffer ?: return
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
    }

    /**
     * One band of [area] — `[top, bottom)` — read out of the two page images and flattened
     * into [out] at [offset], [stride] bytes to a row, as [DITHER_ON] / [DITHER_OFF].
     *
     * The one place the display-side pass reads the page.
     */
    private fun ditherBand(
        area: Rect,
        top: Int,
        bottom: Int,
        out: ByteArray,
        offset: Int,
        stride: Int,
        settled: Boolean,
    ) {
        bandRect.set(area.left, top, area.right, bottom)
        val hasGraphite = readRasterBand(RasterLayer.GRAPHITE, bandRect, bandGraphite)
        val hasInk = readRasterBand(RasterLayer.INK, bandRect, bandInk)
        val hasSheet = readSheetBand(bandRect, bandSheet)
        DitherFlatten.band(
            bandGraphite, hasGraphite, bandInk, hasInk,
            area.left, top, area.width(), bottom - top,
            out, offset, stride, DITHER_ON, DITHER_OFF, settled,
            bandSheet, hasSheet,
        )
    }

    private fun ensureBandScratch(n: Int) {
        if (bandGraphite.size < n) {
            bandGraphite = IntArray(n)
            bandInk = IntArray(n)
            bandSheet = IntArray(n)
            bandOut = IntArray(n)
        }
    }

    /**
     * The dither image at the page's size, re-made when the page changes shape — with
     * [ditherBytes] beside it, sized from `rowBytes` rather than from the width, because
     * an `ALPHA_8` row may be padded and `copyPixelsFromBuffer` copies the bitmap's bytes,
     * padding and all.
     *
     * The two are allocated together and only together: a fresh bitmap is blank, and the
     * array that claims to be its truth has to be blank with it.
     */
    private fun ensureDither(): Bitmap? {
        val w = flattenW
        val h = flattenH
        if (w <= 0 || h <= 0) return null
        val existing = ditherDisplay
        if (existing != null && ditherW == w && ditherH == h && ditherBytes != null) return existing
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val bytes = ByteArray(bitmap.rowBytes * h)
        ditherDisplay = bitmap
        ditherBytes = bytes
        ditherBuffer = java.nio.ByteBuffer.wrap(bytes)
        ditherW = w
        ditherH = h
        return bitmap
    }

    /** Let the dither go — the image and the bytes that are its truth, together. Dropped,
     *  never cleared in place: the committed display list is still holding the bitmap (see
     *  [ditherDisplay]). */
    private fun dropDither() {
        ditherDisplay = null
        ditherBytes = null
        ditherBuffer = null
        ditherW = 0
        ditherH = 0
    }

    /** Rebuild the whole dither and present it — for the moments the *representation*
     *  changed rather than the page: the panel opening under a page already drawn on.
     *  Through the same deferral as a load, so the two can never both run. */
    private fun refreshDitherDisplay() {
        if (!ditherDisplayed && !directStroke) return
        if (ditherCoalescer.onWholePage() == DitherCoalescer.Action.SCHEDULE_WHOLE) {
            post(ditherRebuild)
        }
    }

    /** A direct contact begins: nothing laid yet, nothing to clear, and the view's screen
     *  offset read once (the panel speaks screen coordinates). */
    private fun beginLivePreview() {
        liveEvents = 0
        laidFlecks = 0
        laidInkCount = 0
        contactLayer = RasterLayer.of(penStyle)
        contactInk = pencilInk(penColor)
        contactPenColor = penColor
        // The sweep itself is minted on the first sample: only then is there a pending
        // stroke id to seed it from without asking for one and throwing it away.
        liveSweep = null
        liveRect.setEmpty()
        getLocationOnScreen(contactScreenLoc)
    }

    /** Forget what this contact laid. The panel is not told: the caller either baked the
     *  same pixels (pen-up — the window's frame is the mirror) or re-tones the rect itself
     *  (a cancelled contact). */
    private fun clearLivePreview() {
        if (contactDirect) Log.i(
            TAG,
            "live ${contactLayer.name.lowercase()}: $liveEvents events, $laidFlecks flecks, " +
                "rect $liveRect, grain ${timeGrain / 1_000_000} ms total, tone " +
                "${timeTone / 1_000_000} ms total (max ${maxTone / 1_000_000} ms/event)",
        )
        provisional = false
        liveSweep = null
        timeGrain = 0; timeTone = 0; maxTone = 0
        liveLayer(contactLayer)?.let { clearMaskRect(it, liveRect) }
        laidFlecks = 0
        laidInkCount = 0
        liveRect.setEmpty()
    }

    /**
     * A cancelled direct contact — of either tool: nothing committed, so what is on the
     * panel corresponds to nothing at all. Drop the live layer and re-tone what it covered
     * from the page images alone, which paints the paper back.
     */
    private fun dropLivePreview() {
        if (liveLayer(contactLayer) == null || liveRect.isEmpty) {
            clearLivePreview()
            return
        }
        toneRect.set(liveRect)
        clearLivePreview()
        toneAndPost(toneRect)
    }

    // ── The lasso trail, app-painted (Phase 42, decision 2) ──────────────────

    /** The outline under the pen, laid a segment at a time with its dash phase carried —
     *  see [TrailSweep]. Null between outline contacts. */
    private var trail: TrailSweep? = null

    /** Whether this contact's outline is painted by this view (a direct page), latched at
     *  ACTION_DOWN with the other contact latches. */
    private var contactTrail = false

    /** Whether this contact's trail is the lasso eraser's x-stream rather than the lasso's
     *  dash (Phase 43) — read off the tool at [beginTrail], with [contactTrail]. */
    private var contactTrailCrosses = false

    /** The trail's stroke: the base's selection chrome, aliased — the dash effect is set
     *  per segment with that segment's phase. */
    private val trailPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = TRAIL_WIDTH_PX
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = false
    }
    private val trailPath = Path()

    private fun beginTrail() {
        contactTrail = true
        contactTrailCrosses = tool == Tool.LASSO_ERASER
        trail = null
        laidInkCount = 0
        liveRect.setEmpty()
        // Chrome, in black, on the ink layer — the flatten reads the colour from here.
        contactLayer = RasterLayer.INK
        contactPenColor = Color.BLACK
    }

    /**
     * The outline grew: draw the new stretch — dashes continuing from where the last
     * stretch's left off ([TrailSweep]) — into the ink live layer and show it, exactly as
     * a pen segment is shown ([extendLiveInk]). A single first point is a dot, which is
     * what a tap's trail is on the daemon too.
     */
    override fun onLassoTrailExtended(points: List<StrokePoint>) {
        if (!contactTrail) return
        val mask = ensureLiveLayer(RasterLayer.INK) ?: return
        val crosses = contactTrailCrosses
        val sweep = trail ?: (
            if (crosses) TrailSweep(TRAIL_CROSS_PITCH_PX, crosses = true)
            else TrailSweep(TRAIL_DASH_ON_PX + TRAIL_DASH_OFF_PX)
            ).also { trail = it }
        val seg = sweep.advance(points) ?: return
        if (crosses && seg.marks.isEmpty()) return
        val pad = (if (crosses) ceil(TRAIL_CROSS_ARM_PX + TRAIL_WIDTH_PX).toInt() else ceil(TRAIL_WIDTH_PX / 2f).toInt()) + 2
        toneRect.set(
            floor(seg.minX).toInt() - pad,
            floor(seg.minY).toInt() - pad,
            ceil(seg.maxX).toInt() + pad,
            ceil(seg.maxY).toInt() + pad,
        )
        if (!toneRect.intersect(0, 0, liveAlphaW, liveAlphaH)) return
        val rect = toneRect
        val scratch = ensureBatch(rect.width(), rect.height()) ?: return
        val canvas = batchCanvas ?: return
        val save = canvas.save()
        canvas.clipRect(0, 0, rect.width(), rect.height())
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
        if (crosses) {
            // The lasso eraser's x-stream: the marks this stretch reached, each two lines.
            trailPaint.pathEffect = null
            trailPaint.strokeCap = Paint.Cap.BUTT
            val arm = TRAIL_CROSS_ARM_PX
            var m = 0
            while (m + 1 < seg.marks.size) {
                val x = seg.marks[m]
                val y = seg.marks[m + 1]
                m += 2
                canvas.drawLine(x - arm, y - arm, x + arm, y + arm, trailPaint)
                canvas.drawLine(x - arm, y + arm, x + arm, y - arm, trailPaint)
            }
        } else if (seg.to - seg.from == 1) {
            trailPaint.pathEffect = DashPathEffect(floatArrayOf(TRAIL_DASH_ON_PX, TRAIL_DASH_OFF_PX), seg.phase)
            trailPaint.strokeCap = Paint.Cap.ROUND
            val p = points[seg.from]
            canvas.drawPoint(p.x, p.y, trailPaint)
        } else {
            trailPaint.pathEffect = DashPathEffect(floatArrayOf(TRAIL_DASH_ON_PX, TRAIL_DASH_OFF_PX), seg.phase)
            trailPaint.strokeCap = Paint.Cap.ROUND
            trailPath.rewind()
            trailPath.moveTo(points[seg.from].x, points[seg.from].y)
            for (i in seg.from + 1 until seg.to) trailPath.lineTo(points[i].x, points[i].y)
            canvas.drawPath(trailPath, trailPaint)
        }
        canvas.restoreToCount(save)
        mergeBatchIntoLive(scratch, rect, mask)
        liveRect.union(rect)
        toneAndPost(rect)
    }

    /** The outline is over (closed, cancelled, or the tool changed under it): drop the
     *  trail from the live layer and put the committed picture back under it, in one
     *  post. The selection box, if any, is the window's and follows a beat later. */
    private fun wipeTrail() {
        if (!contactTrail) return
        contactTrail = false
        trail = null
        liveLayer(RasterLayer.INK)?.let { clearMaskRect(it, liveRect) }
        if (!liveRect.isEmpty) postRectFromDither(Rect(liveRect))
        liveRect.setEmpty()
        laidInkCount = 0
    }

    // ── The bake IS the live layer (Phase 29) ────────────────────────────────

    /**
     * Lay this contact's mark into the page image — **by compositing the live layer**, which
     * is already exactly the mark, rather than by rendering the stroke a second time.
     *
     * The base offers this seam ([bakeCapturedStroke]) and everything else about the commit
     * is unchanged: the will-change halves have gone out, the runs are announced right
     * after, `onStrokeCommitted` and the changed halves follow. What changes is where the
     * pixels come from. A pencil's flecks and a pen's segments were drawn into the live
     * layer as the hand made them, at the same coordinates, through the same renderer, in
     * the same colour; compositing that layer with [DitherFlatten.srcOver] — the same
     * arithmetic the live flatten applied to it — leaves the page holding precisely the
     * pixels the panel has been showing. So the mirror at pen-up is exact by construction
     * rather than by two renderings agreeing, and the whole-stroke recompute that cost a
     * dense scribble most of a second on a Nomad is simply not done.
     *
     * The pencil owes one thing first: the **end cap**, which prefix mode never lays because
     * until the pen lifts that end is still travelling ([GraphiteGrain.Sweep.finish]). And a
     * mark still showing its provisional opening (the undecidable first ~50 px) is cleared
     * and re-laid whole from the finished sweep, because provisional flecks are the one
     * thing on this path that are *not* the mark.
     *
     * Returns false — and lets the base composite as it always has — for a contact this
     * path was not previewing at all: a style it cannot preview ([directStyle]), a page
     * with no size yet, or a mark on a layer this contact does not own.
     */
    override fun bakeCapturedStroke(stroke: Stroke, dirty: List<Rect>): Boolean {
        if (!contactDirect) return false
        val layer = RasterLayer.of(stroke.style)
        if (layer != contactLayer) return false
        val mask = liveLayer(layer) ?: return false
        val color: Int
        if (layer == RasterLayer.GRAPHITE) {
            // Through the bake's own seams, exactly as the preview reached them.
            val baked = bakePoints(stroke.points, stroke.style)
            val sweep = liveSweep ?: GraphiteGrain.begin(
                stroke.width, stroke.id.hashCode(), contactInk.density, pencilLead,
            ).also { liveSweep = it }
            if (provisional) {
                // The provisional lay is not the mark: drop it, and let the finish below
                // hand back the whole of the true one (an unstarted sweep lays everything).
                clearMaskRect(mask, liveRect)
                liveRect.setEmpty()
                provisional = false
            }
            val grain = sweep.finish(baked)
            if (grain.count > 0) {
                newFleckBounds(grain, 0)?.let { rect ->
                    layFlecks(grain, 0, rect, mask)
                    liveRect.union(rect)
                    // The cap is the one part of the mark the panel has never seen — it
                    // could not be laid while the pen was still on it. Show it now, from
                    // the live layer, rather than leaving it to the compositor's own
                    // rewrite of the window a beat later.
                    toneAndPost(rect)
                }
            }
            color = contactInk.color
        } else {
            // Whatever of the stroke the last event did not reach — a pen-up carries the
            // final samples, and the segment from the last laid point to them is the only
            // part of the mark not yet on the panel.
            if (stroke.points.size > laidInkCount) {
                val from = if (laidInkCount == 0) 0 else laidInkCount - 1
                val tail = stroke.points.subList(from, stroke.points.size)
                inkBounds(tail)?.let { rect ->
                    layInk(tail, rect, mask)
                    liveRect.union(rect)
                    // The final samples arrive with the lift itself, so this last stretch
                    // is new to the panel too.
                    toneAndPost(rect)
                }
                laidInkCount = stroke.points.size
            }
            color = contactPenColor
        }
        val target = rasterForWrite(layer) ?: return false
        for (r in dirty) compositeLiveInto(target, mask, r, color)
        // The mark stays on the glass as the dither it was drawn as (Phase 32; the pencil
        // too since Phase 34): its runs wait here until the host asks for the settle or the
        // page is reloaded (Phase 37).
        for (r in dirty) {
            val run = Rect(r)
            if (DISPLAY_SETTLES && run.intersect(0, 0, liveAlphaW, liveAlphaH)) pendingRuns.add(run)
        }
        return true
    }

    /**
     * [mask]'s alpha, in [color], composited `SRC_OVER` into [target] over [rect] — the
     * whole of the direct bake, once per run of the mark.
     *
     * Unpremultiplied integers in and out, which is what `getPixels` gives and `setPixels`
     * takes, and the arithmetic is [DitherFlatten.srcOver] because that is the function the
     * live flatten composited the very same alpha with. The rect is clipped to both the page
     * image and the live layer: the page may be larger than the view (nothing outside it can
     * have been drawn) or smaller (nothing outside it is page).
     *
     * **A pixel is taken out of the mask as it lands**, which is what makes "once per run"
     * safe: a mark's runs deliberately **overlap** — the closing point of one run is the
     * first point of the next, so the segment across the boundary lies wholly inside one
     * rect (`RasterDirty.along`) — and a pixel in that overlap composited twice would bake
     * darker than the panel ever showed it. Zeroing is also exactly the clearing
     * [clearLivePreview] is about to do, so it costs nothing.
     */
    private fun compositeLiveInto(target: Bitmap, mask: ByteArray, rect: Rect, color: Int) {
        val left = maxOf(rect.left, 0)
        val top = maxOf(rect.top, 0)
        val right = minOf(rect.right, target.width, liveAlphaW)
        val bottom = minOf(rect.bottom, target.height, liveAlphaH)
        if (right <= left || bottom <= top) return
        val w = right - left
        val h = bottom - top
        ensureToneScratch(w * h)
        val px = tonePix
        target.getPixels(px, 0, w, left, top, w, h)
        var changed = false
        for (y in 0 until h) {
            val maskRow = (top + y) * liveAlphaW + left
            val row = y * w
            for (x in 0 until w) {
                val i = maskRow + x
                val a = mask[i].toInt() and 0xFF
                if (a == 0) continue
                mask[i] = 0
                px[row + x] = DitherFlatten.srcOver(px[row + x], color, a)
                changed = true
            }
        }
        if (changed) target.setPixels(px, 0, w, left, top, w, h)
    }

    /** Draw a run of pen samples into the batch scratch and merge it into [mask] — the
     *  live ink's own [layFlecks]. */
    private fun layInk(points: List<StrokePoint>, rect: Rect, mask: ByteArray) {
        val scratch = ensureBatch(rect.width(), rect.height()) ?: return
        val canvas = batchCanvas ?: return
        val save = canvas.save()
        canvas.clipRect(0, 0, rect.width(), rect.height())
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
        drawPenInk(canvas, points, contactPenColor, penWidth)
        canvas.restoreToCount(save)
        mergeBatchIntoLive(scratch, rect, mask)
    }

    /**
     * One batch of a rubbing sweep has lifted graphite over [rect]: show it, now.
     *
     * The rubber goes through the panel like everything else on a direct page (Phase 29) —
     * the corridor is re-flattened from the page images (the live layers are empty during an
     * erase) and written straight into the driver, so the artist sees graphite coming up
     * under the rubber rather than a frame's worth of it at a time. That is why this
     * engine's [rasterEraseRedrawIntervalMs] is end-only there: the window's own mirror
     * arrives once, at [finalizeEraseRedraw], and nothing in between is needed.
     *
     * The gate is the **page**, not [contactRubbing]: a rub is a rub whichever gesture asked
     * for it — the rubber, the lasso eraser, a scribble — and every one of them is on a page
     * whose cadence is end-only, so a batch not posted here would not be seen until the
     * gesture finished. ([contactRubbing] decides the other half: which overlay machinery to
     * skip at the ends of the contact.)
     */
    /**
     * The finger smudge goes through the panel the way the rubber does (0.1.54): the blended
     * corridor is re-flattened and written straight into the driver per batch, so the artist
     * sees the hatch running together under the finger. A smudge does not settle what is
     * waiting either. The screen offset is taken at [beginSmudge] — for the finger there is
     * no stylus contact to take it at; under [Tool.SMUDGE] the contact took it already and
     * this takes it again, the same answer.
     */
    override fun onRasterSmudgedBatch(rect: Rect) {
        if (!directRaster) return
        toneAndPost(rect)
    }

    override fun beginSmudge() {
        super.beginSmudge()
        if (directRaster) {
            getLocationOnScreen(contactScreenLoc)
            removeCallbacks(smudgeMirror)
        }
    }

    /**
     * On the direct path the corridor is already on the panel batch by batch, so the window's
     * mirror is not needed at every contact end — and a light rub's tip switch chatters, four
     * contacts a second: a frame per contact was one of the two things behind a page going
     * blank on the Nomad (Notesprout SN arc 50, walk 4). The mirror is posted once, a beat
     * after the last contact ends, and every new contact pushes it back.
     */
    override fun onSmudgeEnded() {
        if (!directRaster) { super.onSmudgeEnded(); return }
        removeCallbacks(smudgeMirror)
        postDelayed(smudgeMirror, SMUDGE_MIRROR_DELAY_MS)
    }

    private val smudgeMirror = Runnable { finalizeEraseRedraw() }

    override fun onRasterErasedBatch(rect: Rect) {
        if (!directRaster) return
        // A rub does not settle what is waiting (Phase 37, amending 0.1.49): a corridor
        // through a waiting run posts as the dither around it, and goes to tone with the
        // rest at the host's ask.
        toneAndPost(rect)
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

    /**
     * Whether this erase contact is rubbing a page **we** are painting (Phase 29), latched
     * at ACTION_DOWN like the rest.
     *
     * It decides two things, and both are the absence of something. The corridor is written
     * into the panel as it lifts ([onRasterErasedBatch]) instead of waiting for a redraw;
     * and none of the overlay machinery runs at either end of the contact — no down-time
     * flush, no release, no clear ladder — because the daemon has been full-screen-disabled
     * across this whole page and there is no overlay ink anywhere on it to chase.
     */
    private var contactRubbing = false

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
                    // A direct raster page has no overlay ink anywhere on it — the daemon
                    // is disabled across the whole page — so there is nothing to flush,
                    // release or chase with a ladder for an erase contact there.
                    contactRubbing = contactErasing && direct
                    // The panel speaks screen coordinates and a mid-layout read lies, so
                    // every contact on a direct page takes the offset once, here — an
                    // inking one, a rubbing one, and a lasso or scribble that turns out to
                    // rub after the fact.
                    if (direct) getLocationOnScreen(contactScreenLoc)
                    // Armed gesture-trace clear: normally already flushed from the
                    // hover approach (flushArmedOverlayClearOnApproach — a down-time
                    // clear pairs with a frame presented into THIS contact's ink and
                    // eats it; seen wiping lasso-trail starts on the Nomad). Keep the
                    // down flush only for erase contacts, whose overlay ink is
                    // unwanted anyway.
                    if (contactErasing && !contactRubbing) {
                        if (overlayClearArmed) flushArmedOverlayClear()
                        releaseFirmwareOverlay()
                    }
                    contactLassoOutline = false
                    contactLassoDrag = false
                    contactInking = !contactErasing && tool == Tool.PEN && !firmwareInkSuppressed
                    // Latched like every other contact state: what previews this mark may
                    // not change under it half way through (a host arming the pen mid-stroke
                    // would otherwise leave half a mark on the panel and half on the overlay).
                    contactDirect = contactInking &&
                        ((directRaster && directStyle(penStyle)) || (directStroke && directInkStyle(penStyle)))
                    if (contactDirect) beginLivePreview()
                    Log.i(
                        TAG,
                        "contact: direct=$contactDirect rubbing=$contactRubbing " +
                            "inking=$contactInking panel=${panel.isOpen} " +
                            "mode=$pageMode directInk=$directInk style=$penStyle tool=$tool " +
                            "suppressed=$firmwareInkSuppressed",
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
                    // On a direct page the daemon that drew the dashed trail is off: this
                    // view paints it (Phase 42, decision 2), for either lasso.
                    if (contactLassoOutline && direct) beginTrail()
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
            if (contactRubbing) {
                // Nothing at all. The daemon painted nothing on this page, so there is no
                // trace to chase — and the base has already run finalizeEraseRedraw at the
                // gesture's end, which is the one redraw this sweep gets (the cadence is
                // end-only here) and the window's mirror of what the panel already shows.
            } else if (contactErasing) {
                // Every erase contact ends with the clear ladder: the pen-down
                // bake+clear can be eaten (an erased stroke's overlay twin stays frozen
                // on the panel, hiding the repaint), and a contact that armed the
                // eraser mid-approach leaves a partial pen trace. Idempotent when clean.
                releaseGestureTrace()
            } else if (contactLassoOutline) {
                // The dashed trail (a tap paints a dash dot too) corresponds to nothing
                // in the app layer — wipe it with the proven gesture-trace ladder. The
                // base has already drawn the selection box by now (super ran first). On a
                // direct page the trail is this view's own pixels: re-present the
                // committed picture under it.
                if (contactTrail) wipeTrail() else releaseGestureTrace()
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
            contactRubbing = false
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
        swappingContent = true
        try {
            super.clearForContentSwap()
        } finally {
            swappingContent = false
        }
    }

    /** True only inside [clearForContentSwap]'s own whole-page news, which drops the dither
     *  even with a sheet set — see [onRasterPixelsChanged] (Phase 46). */
    private var swappingContent = false

    override fun setTemplate(bitmap: Bitmap?) {
        releaseFirmwareOverlay()
        super.setTemplate(bitmap)
    }

    /** As [setTemplate]: the overlay goes first. The base then announces the whole page
     *  ([onRasterPixelsChanged] null), so the dither is rebuilt with the sheet under it and
     *  presented once, through the same coalescer a page load uses (Phase 46). */
    override fun setSheet(bitmap: Bitmap?) {
        releaseFirmwareOverlay()
        super.setSheet(bitmap)
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
        // The panel driver, before the tool push: [applyToolToFirmware] asks [directRaster]
        // whether to arm the needle or disable the daemon, and that answer depends on this.
        // Idempotent, and a refusal is remembered — every focus gain runs this method.
        if (isRattaDevice()) {
            val wasOpen = panel.isOpen
            panel.open(maxOf(screenW, width), maxOf(screenH, height))
            // Opening it changes what the window shows (true greys → the dither), not what
            // the page holds — so unlike every other change here, this one needs the
            // committed layer rebuilt and presented. Once per session: the call is
            // idempotent and every focus gain comes through here.
            if (!wasOpen && panel.isOpen) {
                refreshDitherDisplay()
                // The other order of the same event: the page was already raster and the
                // panel has just come up under it.
                announceDirectPath()
            }
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
        // Before the fd goes: nothing may be left holding back a redraw for a rebuild that
        // will never run (deferRedraw would swallow every frame this view ever presents
        // again).
        removeCallbacks(ditherRebuild)
        ditherCoalescer.reset()
        panel.close()
        liveGraphite = null
        liveInk = null
        liveAlphaW = 0
        liveAlphaH = 0
        batchCanvas = null
        batchBitmap = null
        trail = null
        contactTrail = false
        // No redraw: this runs at detach and at release, where the view has nothing left
        // to show anyone. The dither simply stops being what [drawRasterLayers] answers.
        dropDither()
        dropCommittedPage()
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
        removeCallbacks(smudgeMirror)
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
        removeCallbacks(smudgeMirror)
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
