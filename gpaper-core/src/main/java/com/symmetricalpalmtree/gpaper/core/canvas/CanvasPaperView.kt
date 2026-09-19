package com.symmetricalpalmtree.gpaper.core.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.gpaper.core.PageMode
import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.gpaper.core.RasterRubbing
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.RasterPatch
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.RawAction
import com.symmetricalpalmtree.gpaper.core.RawInputEvent
import com.symmetricalpalmtree.gpaper.core.RawInputListener
import com.symmetricalpalmtree.gpaper.core.RawTool
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.geometry.EraseHitTest
import com.symmetricalpalmtree.gpaper.core.geometry.GestureRecognizer
import com.symmetricalpalmtree.gpaper.core.geometry.LassoHitTest
import com.symmetricalpalmtree.gpaper.core.geometry.RasterDirty
import com.symmetricalpalmtree.gpaper.core.geometry.RasterErase
import com.symmetricalpalmtree.gpaper.core.geometry.RasterRub
import com.symmetricalpalmtree.gpaper.core.geometry.SnapEngine
import com.symmetricalpalmtree.gpaper.core.geometry.SnapGuide
import com.symmetricalpalmtree.gpaper.core.geometry.TransformGeometry
import com.symmetricalpalmtree.gpaper.core.geometry.TransformGrab
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.gpaper.core.model.toRectOut
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
 * - **Raster pages (0.1.25)** — in [PageMode.RASTER] the committed layer's stroke loop is
 *   replaced by a blit of the page image, a page-sized transparent bitmap the marks are
 *   composited into at pen-up through the very same [StrokeRenderer]. Everything before
 *   pen-up is shared with stroke mode; only what is *kept* differs. See [pageMode].
 *   **Two images since 0.1.39** ([RasterLayer]): [graphiteRaster] and [inkRaster],
 *   routed by style, flattened with `DARKEN` — so the rubber can lift graphite and leave
 *   ink, which one bitmap could never do because a pixel does not know what laid it.
 *
 * Input is stylus-only: finger events are never consumed, so host gestures work above and
 * around the paper. The pen-activity gate ([isPenActive]) tracks every captured stylus
 * contact plus a [PaperView.PEN_ACTIVE_TAIL_MS] tail.
 */
open class CanvasPaperView(context: Context) : View(context), PaperView {

    private companion object {
        const val TAG = "GPaperCore"

        /** Pen-gesture recognition (candidates + hit tests) runs synchronously in the
         *  commit path; log any pass that exceeds this so slow-hardware cost is
         *  observable in logcat instead of read as mystery input lag. */
        const val GESTURE_RECOGNITION_BUDGET_MS = 8L

        /** Redraw at most this often while the eraser sweeps (erase-path performance rule). */
        const val ERASE_REDRAW_INTERVAL_MS = 60L

        /**
         * The raster eraser's own cadence (0.1.26): one frame, not 60 ms. Rubbing is
         * judged by the hand as it happens, and the artist felt the stroke-mode
         * interval as lag. Measured on the NoteAir5C before it was changed: the
         * re-record is under a millisecond (one `drawBitmap`), the frame about 14 ms
         * with the page bitmap's upload 4 ms of it, and the regional panel repaint
         * returns in 2 ms — so the 60 ms was most of what the software added on top of
         * the panel's own update, and the only lever left in the engine.
         */
        const val RASTER_ERASE_REDRAW_INTERVAL_MS = 16L

        /**
         * The [rasterEraseRedrawIntervalMs] value meaning "no mid-sweep redraw at
         * all": the sweep is presented once, at its end, by [finalizeEraseRedraw].
         * A sentinel rather than a flag because the cadence is one number an engine
         * measures, and "never" is the far end of the same scale.
         */
        const val RASTER_ERASE_REDRAW_END_ONLY = Long.MAX_VALUE

        /**
         * How far a mark may wander before its announcement is cut into another rect
         * (0.1.33, [RasterDirty.along]), and the most rects one mark may become.
         *
         * 256 px is a few before-image cells on the 64 px grid the API document
         * recommends — fine enough that a long diagonal announces the ink rather than
         * the page, coarse enough that an ordinary word is still one rect and the host
         * pays one read. 64 bounds the pathological case; past it the last run absorbs
         * the tail. Both are candidates until the Notesprout SN arc 43 K6 Nomad walk
         * measures them on a real page (cells read, entry bytes, pen-up main-thread ms).
         */
        internal const val RASTER_DIRTY_SPAN_PX = 256

        /** See [RASTER_DIRTY_SPAN_PX]. */
        internal const val RASTER_DIRTY_MAX_RECTS = 64

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

        /**
         * How near a guide a dragged selection must come to catch it, in dp
         * (reference value). Small on purpose: a guide holds only while the pen stays
         * inside this, so dragging on always releases and snapping never reads as the
         * page resisting the hand. See [PaperView.snapToGuides].
         */
        const val SNAP_THRESHOLD_DP = 20f
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

    /**
     * The graphite page image, raster mode only: page-sized, transparent where nothing has
     * been drawn, allocated the first time raster content needs somewhere to land and
     * dropped on every content swap. [StrokeStyle.PENCIL] bakes here, and this is the only
     * image the rubber ever reads or writes.
     *
     * It is a layer *over* the paper and never the paper itself. White and the template
     * still draw underneath it in [drawCommittedContent], so a mark is graphite on the
     * sheet rather than a picture of a sheet with graphite on it — which is what lets an
     * eraser clear pixels to transparent (0.1.26) instead of painting white holes, and
     * lets a textured paper sit under a raster page one day without the image having to
     * know. ARGB_8888 rather than ALPHA_8: about 18 MB at a 1860 × 2480 e-ink page, but
     * the colour a stroke carries is kept rather than re-applied as a tint, so a colour
     * panel is not locked out by the storage format.
     *
     * Dropped, not erased, at a swap: the committed display list holds its own reference
     * to the bitmap it was recorded with, so releasing ours keeps the old pixels on the
     * panel until the next content lands — the same "pixels hold" contract
     * [clearForContentSwap] makes for strokes. Erasing it in place would blank the
     * screen at the next frame, before the new page arrived.
     */
    private var graphiteRaster: Bitmap? = null

    /**
     * The ink page image (0.1.39): [graphiteRaster]'s twin in every respect — same size,
     * same format, same lazy allocation, same dropped-not-erased rule — holding every
     * style that is not the pencil.
     *
     * It is a second bitmap rather than a flag on the first because **a pixel does not
     * know which tool laid it**: the rubber lifts alpha wherever it sweeps, so one image
     * meant a gel pen came up exactly as graphite did, and no colour key can separate a
     * black pen from a black pencil honestly. The artist's rule is the physical one — ink
     * is more permanent than pencil — so the answer is the page's data model. The two are
     * flattened with `DARKEN` wherever the page is seen ([drawCommittedContent]), which is
     * order-independent: there is no top layer here and nothing for a host to z-order.
     *
     * The cost is a second page-sized bitmap **only once ink lands** — a pencil-only page
     * never allocates it, and neither does an erase, which never reads this image at all.
     */
    private var inkRaster: Bitmap? = null

    /**
     * The flatten (0.1.39): the ink image goes over the graphite one through
     * `PorterDuff.Mode.DARKEN` — the darker of the two per channel.
     *
     * `DARKEN` rather than the ordinary over-draw because a flatten must not have a top
     * and a bottom. Neither raster is "above" the other in anything the artist did: they
     * are two media on one sheet, and `min` is commutative, so the picture is the same
     * whichever is painted first. It is also the right answer for a coloured ink later
     * (two transparent media overlaid darken each channel independently), and on white
     * paper with grey marks it is pixel-identical to `SRC_OVER`, so nothing about the
     * pencil-only page the artist already approved moves.
     *
     * Allocated once and reused; a `Paint` per frame is a page's worth of garbage on a
     * panel that re-records whenever anything changes.
     */
    private val flattenPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
    }

    private val contentRenderers = ArrayList<ContentRenderer>()

    // ── Input state ──────────────────────────────────────────────────────────

    private val activePoints = ArrayList<StrokePoint>()

    /**
     * The id the stroke now under the pen will be committed with, minted the first time
     * anything needs it rather than at pen-down.
     *
     * A textured style seeds its texture off the stroke's id, and the live preview is drawn
     * through the same renderer as the bake — so if the id were minted at commit time, a
     * pencil stroke would visibly reshuffle its grain the instant the pen left the paper.
     * Every mark would end with a flinch. Minting it here instead means the preview and the
     * committed stroke are the same stroke all along.
     *
     * A contact that is cancelled or consumed as a gesture leaves its unused id behind for
     * the next stroke to pick up, which costs nothing: no ink was ever laid down with it.
     * Where one contact legally commits several strokes (the Onyx SDK can deliver more than
     * one batch per contact), the first takes this id and the rest mint their own.
     */
    private var pendingStrokeId: String? = null
    private var gestureMode = GestureMode.NONE
    private var lastEraserPoint: StrokePoint? = null
    private var lastEraseRedrawMs = 0L

    /**
     * The page-space patch a raster erase has cleared since the panel last saw it —
     * the union of the batch rects behind the throttle, handed to
     * [presentRasterEraseProgress] when the throttle lets a redraw through, and
     * dropped at sweep end because the end-of-sweep repaint covers the whole view.
     */
    private var rasterErasePending: Rect? = null

    /**
     * The rubbing eraser's state for the contact in progress (0.1.30) — see [RasterRub]
     * for the model. [rubPass] is the page-sized byte mask of how much this pass has
     * lifted each pixel so far; [rubPassRect] is the page rect it has touched, so that
     * dropping the pass at a reversal or a fresh contact clears only what was written;
     * [rubDirection] is the last batch's travel, which a reversal is judged against;
     * [rubPixels] is the reused scratch for one batch's rect of page pixels.
     *
     * 0.1.26 stroked the sweep onto the page in `CLEAR` — a rubber that cut a hole in
     * one pass, with an edge that showed. The artist drew with it for an afternoon and
     * asked for graphite that *lightens*, a corridor whose edge fades, and a smaller
     * rubber; the first two are this, the third is the host's radius. Nothing about
     * *what is announced* changed: will-change with the batch rect before the pixels
     * move, changed after, the throttled regional repaint between — the host's undo and
     * the panel's live rubbing both ride on the same calls as before.
     */
    private var rubPass: ByteArray? = null
    private var rubPassRect: Rect? = null
    private var rubDirection: FloatArray? = null
    private var rubPixels = IntArray(0)

    /** Content ids already reported to [PaperListener.onContentErased] this erase gesture —
     *  the host removes content asynchronously, so its hit target can outlive the report by
     *  several sweep batches. Cleared at each fresh sweep start ([eraseAlong] with no chained
     *  previous sample). */
    private val reportedContentErases = HashSet<String>()

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

    /** True from a smart-lasso trigger (which switches [tool] to [Tool.LASSO]) until
     *  the session's selection lifecycle fully ends — the component then restores
     *  [Tool.PEN] ([maybeEndSmartLassoSession]). Cleared by any tool assignment. */
    private var smartLassoSession = false

    /** Held around the dismissal a NEW outline performs at pen-down: that dismissal is
     *  not the end of a smart-lasso session — the outline may create the successor. */
    private var suppressSmartLassoRestore = false

    /** Outline capture for THIS class's MotionEvent path. Device engines whose pipeline
     *  owns the pen (Onyx raw callbacks) buffer their own points and drive the shared
     *  entries ([lassoTryBeginDrag] / [lassoOutlineStart] / [completeLassoOutline]). */
    private val lassoPoints = ArrayList<StrokePoint>()
    private var lassoCapturing = false
    private var lastLassoInvalidateMs = 0L

    /** Whether the outline now in flight dismissed a selection at pen-down (0.1.5). That
     *  contact is spent on the dismissal, so a tap-sized one must not also report
     *  [PaperListener.onPaperTapped]. Latched in [lassoOutlineStart], read and cleared in
     *  [completeLassoOutline]. */
    private var outlineDismissedSelection = false

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

    /**
     * The guide rule drawn where a snapped drag caught. Same weight and blackness as
     * [selectionPaint] — whole pixels, because a sub-pixel hairline is a coin flip on an
     * EPD panel at a fractional density — but a much longer dash stride, so a full-length
     * ruler never reads as another selection box. Butt caps keep the dash length honest.
     */
    private val snapGuidePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(24f, 12f), 0f)
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = false
    }

    /** Guides the live drag is currently caught on — at most one per axis, empty
     *  whenever [snapToGuides] is off or nothing is in range. */
    private var activeSnapGuides: List<SnapGuide> = emptyList()

    /** Tight bounds of the non-selected content objects, snapshotted at drag start so
     *  the per-sample snap costs no renderer calls; empty outside a snapped drag. */
    private var snapTargets: List<Bounds> = emptyList()

    // ── Transform mode state (0.1.27) ────────────────────────────────────────

    /**
     * One host object under transform. [box] is live (the gesture in flight has already
     * moved it); [gestureStart] is the box the current contact began on — every sample
     * is computed from it and the current point, never accumulated. [renderer] is the
     * content renderer that owns [contentId] (found once at begin), drawn live through
     * [ContentRenderer.drawObject]; null when no renderer claims the id, in which case
     * the overlay's dashed box is the only visual.
     */
    private class TransformState(
        val contentId: String,
        val before: OrientedBox,
        var box: OrientedBox,
        var aspectLocked: Boolean,
        val minSizePx: Float,
        val renderer: ContentRenderer?,
    ) {
        var grab = TransformGrab.NONE
        var gestureStart = box
        var startX = 0f
        var startY = 0f
        /** The last box handed to [PaperListener.onTransformChanged]. */
        var reported: OrientedBox = box
    }

    private var transform: TransformState? = null
    private var lastTransformReportMs = 0L
    private val transformOverlay by lazy { TransformOverlay(resources.displayMetrics.density) }

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
            // Any tool assignment ends a smart-lasso session — whoever set the tool
            // (the host, or the session's own PEN restore) now owns tool state.
            smartLassoSession = false
            cancelActiveGesture()
            // A tool change is a transform exit (the mode requires LASSO).
            endActiveTransform()
            // Leaving LASSO drops its selection; arming the lasso eraser drops any
            // host-injected one too — there is no selection in that tool (0.1.28).
            if (leavingLasso || value == Tool.LASSO_ERASER) clearSelection()
        }

    override var penColor: Int = Stroke.BLACK

    override var penWidth: Float = Stroke.DEFAULT_WIDTH

    override var penStyle: StrokeStyle = StrokeStyle.PEN

    override var eraserRadius: Float = DEFAULT_ERASER_RADIUS_PX
    override var rasterRubbing: RasterRubbing = RasterRubbing()

    override var pageMode: PageMode = PageMode.STROKE
        set(value) {
            if (field == value) return
            // A mode is set on an empty page. Dropping the content through the swap call
            // (open — device engines add their overlay release there) means the pixels on
            // the panel hold until the host loads what the new mode understands, so a
            // book opened in either mode turns its first page as quietly as any other.
            clearForContentSwap()
            field = value
        }

    override var smartLassoEnabled: Boolean = false

    override var scribbleEraseEnabled: Boolean = false

    // ── PaperView: snap to guides ────────────────────────────────────────────

    // Off by default, so hosts that never set it pay nothing: the target snapshot is
    // taken only when a drag begins with snapping already armed.
    override var snapToGuides: Boolean = false

    override var snapMarginPx: Float = 0f

    // ── PaperView: stroke data in ────────────────────────────────────────────

    // Every external model mutation dismisses an active selection first: the selected
    // ids may be about to disappear, and a stale box over changed content is worse than
    // asking the host to re-select (paste flows call setSelection right after anyway).

    override fun loadStrokes(strokes: List<Stroke>) {
        endActiveTransform()
        clearSelection()
        if (pageMode == PageMode.RASTER) {
            // The one-way bake: a stroke page's rows land as pixels, then the objects go.
            // A load replaces, so both images start blank — the whole page changes, on
            // both layers. Both are announced even when one of them is empty: a host
            // undoing a load has to be able to put back what each layer held, and "it
            // held nothing" is a before-image like any other.
            val dirty = RasterDirty.wholePage(pageWidth, pageHeight)?.toRectOut()
            dirty?.let {
                paperListener?.onRasterWillChange(RasterLayer.GRAPHITE, it)
                paperListener?.onRasterWillChange(RasterLayer.INK, it)
            }
            dropRasters()
            compositeIntoRaster(strokes)
            redrawCommitted()
            dirty?.let {
                paperListener?.onRasterChanged(RasterLayer.GRAPHITE, it)
                paperListener?.onRasterChanged(RasterLayer.INK, it)
            }
            return
        }
        strokeList.clear()
        strokeList.addAll(strokes)
        modelChanged()
        redrawCommitted()
    }

    override fun addStrokes(strokes: List<Stroke>) {
        endActiveTransform()
        clearSelection()
        if (pageMode == PageMode.RASTER) {
            if (strokes.isEmpty()) return
            // Every mark announces itself as its own run of rects (0.1.33) on its own
            // layer (0.1.39), and the strokes go in as one composite — so all the
            // will-changes first, in the order the runs will be laid down, then the
            // pixels, then all the changed in that same order.
            val dirty = strokes.map { RasterLayer.of(it.style) to rasterDirtyAlong(it) }
            for ((layer, rects) in dirty) {
                for (r in rects) paperListener?.onRasterWillChange(layer, r)
            }
            compositeIntoRaster(strokes)
            redrawCommitted()
            for ((layer, rects) in dirty) {
                for (r in rects) paperListener?.onRasterChanged(layer, r)
            }
            return
        }
        strokeList.addAll(strokes)
        modelChanged()
        redrawCommitted()
    }

    override fun removeStrokes(ids: Collection<String>) {
        endActiveTransform()
        clearSelection()
        // In raster mode there is nothing to remove by id — the objects were let go at
        // commit. A host that undoes a raster mark does it with a before-image.
        val idSet = ids as? Set<String> ?: ids.toHashSet()
        if (strokeList.removeAll { it.id in idSet }) {
            modelChanged()
            redrawCommitted()
        }
    }

    override fun getStrokes(): List<Stroke> = strokeSnapshot

    override fun clear() {
        endActiveTransform()
        clearSelection()
        activePoints.clear()
        strokeList.clear()
        modelChanged()
        if (pageMode == PageMode.RASTER) {
            // Both layers go, and both are announced, graphite first — a page cleared
            // to blank paper changed everything the artist can see.
            val dirty = RasterDirty.wholePage(pageWidth, pageHeight)?.toRectOut()
            dirty?.let {
                paperListener?.onRasterWillChange(RasterLayer.GRAPHITE, it)
                paperListener?.onRasterWillChange(RasterLayer.INK, it)
            }
            dropRasters()
            redrawCommitted()
            dirty?.let {
                paperListener?.onRasterChanged(RasterLayer.GRAPHITE, it)
                paperListener?.onRasterChanged(RasterLayer.INK, it)
            }
            return
        }
        redrawCommitted()
    }

    override fun clearForContentSwap() {
        // Model drops now; pixels stay — no re-record, no invalidate. The next
        // loadStrokes() (or other content call) swaps the screen in one repaint.
        // Both page images go the same way: dropped, not erased (see [graphiteRaster]).
        endActiveTransform()
        clearSelection()
        activePoints.clear()
        strokeList.clear()
        modelChanged()
        dropRasters()
    }

    // ── PaperView: the raster page (0.1.25) ──────────────────────────────────

    override fun loadPageRaster(layer: RasterLayer, bitmap: Bitmap?) {
        if (pageMode != PageMode.RASTER) return
        endActiveTransform()
        clearSelection()
        // Silent (0.1.33): the host replaced its own page, so this is the host's own
        // news — as it already was for swapPageRaster. Announcing it made every host
        // carry a "we are loading, ignore the callbacks" flag, which only ever worked
        // because these calls happen to be synchronous.
        //
        // One layer at a time: the other is left exactly as it was, so a host loading a
        // two-raster page makes two calls and one that only ever drew a pencil makes the
        // one it always did.
        dropRaster(layer)
        if (bitmap != null) {
            // Copied in, at 1:1 from the origin. The host keeps the bitmap it handed us
            // (it may be the decode it is about to recycle, or the undo image it is about
            // to reuse), and we keep one that nothing but this view writes to.
            ensureRaster(layer)?.let { Canvas(it).drawBitmap(bitmap, 0f, 0f, null) }
        }
        redrawCommitted()
    }

    override fun getPageRaster(layer: RasterLayer): Bitmap? {
        val src = raster(layer) ?: return null
        return src.copy(Bitmap.Config.ARGB_8888, false)
    }

    override fun copyPageRaster(layer: RasterLayer, rect: Rect): Bitmap? {
        val src = raster(layer) ?: return null
        val clipped = Rect(rect)
        if (!clipped.intersect(0, 0, src.width, src.height)) return null
        if (clipped.isEmpty) return null
        // Drawn into a fresh bitmap rather than Bitmap.createBitmap(src, x, y, w, h),
        // which hands back the *source* when the subset is the whole of it — and a
        // "copy" that is the live image would let a save encode pixels still being drawn.
        val out = Bitmap.createBitmap(clipped.width(), clipped.height(), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, clipped, Rect(0, 0, clipped.width(), clipped.height()), null)
        return out
    }

    override fun readPageRaster(layer: RasterLayer, rect: Rect): RasterPatch? {
        if (pageMode != PageMode.RASTER) return null
        val w = if (pageWidth > 0) pageWidth else width
        val h = if (pageHeight > 0) pageHeight else height
        val clipped = Rect(rect)
        if (!clipped.intersect(0, 0, w, h) || clipped.isEmpty) return null
        val pixels = IntArray(clipped.width() * clipped.height())
        // A layer with no image yet is transparent everywhere, and a fresh IntArray is
        // exactly that: the before-image of the first mark is nothing, read for free —
        // which is also why the ink layer costs a pencil-only page nothing to read.
        raster(layer)?.getPixels(
            pixels, 0, clipped.width(), clipped.left, clipped.top, clipped.width(), clipped.height(),
        )
        return RasterPatch(clipped, pixels)
    }

    /**
     * One row of page pixels, reused across every swap so a page-wide undo allocates
     * nothing but the log line it does not print. Sized to the widest row asked for.
     */
    private var swapRow = IntArray(0)

    override fun swapPageRaster(layer: RasterLayer, patches: List<RasterPatch>) {
        if (pageMode != PageMode.RASTER || patches.isEmpty()) return
        endActiveTransform()
        clearSelection()
        // The layer is allocated if it is not there: swapping a before-image onto a blank
        // page is the redo of a first mark that was undone back to nothing, and the
        // pixels have to land somewhere.
        val target = ensureRaster(layer) ?: return
        var swapped = false
        for (patch in patches) {
            val r = patch.rect
            if (r.left < 0 || r.top < 0 || r.right > target.width || r.bottom > target.height) {
                Log.w(TAG, "raster patch $r is not on the ${target.width}×${target.height} page; skipped")
                continue
            }
            val w = r.width()
            if (swapRow.size < w) swapRow = IntArray(w)
            val row = swapRow
            val px = patch.pixels
            // Row by row through one buffer, rather than the whole patch through a second
            // array: the patch a page-wide erase leaves behind is the page, and a copy of
            // the page made in the middle of undoing it is the allocation that gets the
            // process killed on a device already holding two of them.
            for (y in 0 until r.height()) {
                val offset = y * w
                target.getPixels(row, 0, w, r.left, r.top + y, w, 1)
                target.setPixels(px, offset, w, r.left, r.top + y, w, 1)
                System.arraycopy(row, 0, px, offset, w)
            }
            swapped = true
        }
        if (swapped) redrawCommitted()
    }

    /** [layer]'s page image as it stands, or null when nothing has landed on it yet. */
    private fun raster(layer: RasterLayer): Bitmap? =
        if (layer == RasterLayer.GRAPHITE) graphiteRaster else inkRaster

    /**
     * [layer]'s page image, allocated if it is not there yet, or null when there is no
     * page to size it by: the page rect if the host set one, else the laid-out view. A
     * view asked to keep raster content before either is known has nowhere to put it, and
     * says so in the log rather than guessing a size the page will not turn out to be.
     *
     * Lazy per layer, not per page: a page drawn only in pencil never allocates the ink
     * image, and the second bitmap is the price of the first mark made with a pen.
     */
    private fun ensureRaster(layer: RasterLayer): Bitmap? {
        raster(layer)?.let { return it }
        val w = if (pageWidth > 0) pageWidth else width
        val h = if (pageHeight > 0) pageHeight else height
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "raster page has no size yet (setPageSize or layout first); content dropped")
            return null
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        when (layer) {
            RasterLayer.GRAPHITE -> graphiteRaster = bitmap
            RasterLayer.INK -> inkRaster = bitmap
        }
        return bitmap
    }

    /** Let go of one layer's image — dropped, not erased (see [graphiteRaster]). */
    private fun dropRaster(layer: RasterLayer) {
        when (layer) {
            RasterLayer.GRAPHITE -> graphiteRaster = null
            RasterLayer.INK -> inkRaster = null
        }
    }

    /** Let go of the whole page: every call that drops one image drops both. */
    private fun dropRasters() {
        graphiteRaster = null
        inkRaster = null
    }

    /**
     * Lay [strokes] into the page images through the same renderer that bakes them in
     * stroke mode, seeded by the same ids, so the pixels are the ones the artist approved
     * on paper — fleck for fleck what [StrokeRasterizer] would make of the same rows.
     *
     * Each stroke goes to the layer its style routes to ([RasterLayer.of]) — one `Canvas`
     * per layer anything actually lands in, so a page of pencil never touches the ink
     * image and never allocates it.
     */
    private fun compositeIntoRaster(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        val canvases = HashMap<RasterLayer, Canvas>(2)
        for (s in strokes) {
            val layer = RasterLayer.of(s.style)
            val canvas = canvases[layer] ?: run {
                // No page size yet: ensureRaster has logged it, and there is nowhere for
                // any of these strokes to land. Stop rather than log once per stroke.
                val target = ensureRaster(layer) ?: return
                Canvas(target).also { canvases[layer] = it }
            }
            StrokeRenderer.draw(
                canvas, bakePoints(s), s.color, s.width, s.style, scratchPaint, s.id.hashCode(),
            )
        }
    }

    /**
     * The pressure a sample bakes with on a raster page — identity, unless a device
     * engine overrides [bakePressure].
     *
     * Applied here, at the one place a raster page is written, so it covers a fresh mark,
     * a `loadStrokes` bake and an `addStrokes` bake alike — and nowhere else: the
     * [Stroke] handed to [PaperListener.onStrokeCommitted] keeps the pressures the
     * digitizer reported, because in stroke mode that object is the host's data and the
     * host owns it.
     */
    private fun bakePoints(stroke: Stroke): List<StrokePoint> {
        val pts = stroke.points
        // Identity by default, so nothing is copied on an engine that doesn't override
        // the seam. Raw bits rather than ==, so an unreported NaN pressure compares
        // equal to itself instead of faking a change and copying the whole polyline.
        val differs = pts.any {
            bakePressure(stroke.style, it.pressure).toRawBits() != it.pressure.toRawBits() ||
                bakeTilt(stroke.style, it.tilt).toRawBits() != it.tilt.toRawBits()
        }
        if (!differs) return pts
        return pts.map {
            it.copy(
                pressure = bakePressure(stroke.style, it.pressure),
                tilt = bakeTilt(stroke.style, it.tilt),
            )
        }
    }

    /**
     * What tilt a captured sample should bake with on a **raster page** — [bakePressure]'s
     * twin, for the same reason: the preview and the bake must agree. The default is the
     * tilt that was reported.
     *
     * The case it exists for: a leaned `PENCIL` bakes with the flank of the lead, up to
     * ~11× the set width, and Supernote's firmware line cannot widen with lean at all. A
     * hairline drawn at an ordinary writing angle previewed as a hairline and baked as a
     * broad band (found on the Manta, 0.1.35). Ratta therefore bakes its `PENCIL` upright.
     */
    protected open fun bakeTilt(style: StrokeStyle, tilt: Float): Float = tilt

    /**
     * What pressure a captured sample should bake with on a **raster page** — the whole
     * point being that the preview and the bake agree, so this is where an engine gives
     * up a tone its hardware cannot show. The default is the pressure that was reported,
     * and Onyx and the generic engine keep it.
     *
     * The case it exists for: Supernote's firmware paints **one tone per armed pen**. No
     * choice of firmware grey can track a soft touch, so a lightly drawn line always
     * previews darker than a pressure-toned bake of it — and the fix cannot be on the
     * preview's side, because there is nothing there to vary. Ratta therefore bakes its
     * `PENCIL` at a constant pressure: the mark on the panel is the mark that was drawn,
     * at the cost of a tonal range the panel was never going to preview anyway.
     *
     * Stroke mode is untouched — the kept [Stroke] is the host's data, and a host that
     * persists a pressure must get the one that was measured.
     */
    protected open fun bakePressure(style: StrokeStyle, pressure: Float): Float = pressure

    /**
     * The page-space patches a stroke may touch when composited, in the order it was
     * drawn — see [RasterDirty.along].
     *
     * One rect per mark was the rule until 0.1.33, and it made a corner-to-corner
     * hairline announce the whole page: the host's before-image is the *announced* area,
     * not the ink's, so a long diagonal cost it every undo byte a page has. The run form
     * follows the polyline instead. Nothing else changes — each rect is what
     * [RasterDirty.of] makes of its run, generous and page-clipped, and a short mark
     * still comes back as the single rect it always did.
     */
    private fun rasterDirtyAlong(stroke: Stroke): List<Rect> {
        val w = if (pageWidth > 0) pageWidth else width
        val h = if (pageHeight > 0) pageHeight else height
        return RasterDirty.along(
            points = stroke.points,
            width = stroke.width,
            pageWidth = w,
            pageHeight = h,
            maxSpanPx = RASTER_DIRTY_SPAN_PX,
            maxRects = RASTER_DIRTY_MAX_RECTS,
        ).map { it.toRectOut() }
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
        dragDx = 0f
        dragDy = 0f
        dragStrokes = emptyList()
        dragContentTargets = emptyList()
        snapTargets = emptyList()
        activeSnapGuides = emptyList()
        val hadHidden = dragHiddenIds.isNotEmpty() || dragHiddenContentIds.isNotEmpty()
        dragHiddenIds = emptySet()
        dragHiddenContentIds = emptySet()
        if (hadDragVisual) onSelectionDragVisual(false)
        val had = selection != null
        selection = null
        if (hadHidden) redrawCommitted() else if (had) invalidate()
        if (had) paperListener?.onSelectionDismissed()
        maybeEndSmartLassoSession()
    }

    override fun setSelection(strokeIds: Set<String>, contentIds: Set<String>, bounds: Bounds) {
        // Host-initiated (paste flow): no onSelectionCreated echo — the host already knows.
        endActiveTransform()
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
        transform = null
        smartLassoSession = false
        dragActive = false
        dragThresholdMet = false
        dragDx = 0f
        dragDy = 0f
        dragStrokes = emptyList()
        dragContentTargets = emptyList()
        snapTargets = emptyList()
        activeSnapGuides = emptyList()
        dragHiddenIds = emptySet()
        dragHiddenContentIds = emptySet()
        lassoCapturing = false
        lassoPoints.clear()
        activePoints.clear()
        strokeList.clear()
        modelChanged()
        contentRenderers.clear()
        templateBitmap = null
        dropRasters()
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
                // The stylus is still physically on the glass, though — pulse the gate
                // tail so a resting palm can't pass host palm-gates during the press.
                // (Returning false means no further events for this contact arrive
                // here, so a full markPenDown would never see its markPenUp; on
                // hardware with hover reporting the hover stream keeps the gate closed
                // for the rest of the press.)
                if (exclusionRects.any { it.contains(event.x.toInt(), event.y.toInt()) }) {
                    markPenUp()
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
                    // The lasso eraser captures the same outline and never drags: there is
                    // no box to grab (0.1.28). completeLassoOutline tells the two apart.
                    tool == Tool.LASSO_ERASER -> {
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
                            // A gesture-consumed stroke is chrome, not writing — the
                            // reference contract: no onPenLifted for it.
                            if (commitActiveStroke()) paperListener?.onPenLifted()
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
                            // A cancelled outline creates no successor selection.
                            maybeEndSmartLassoSession()
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

    // Pointer-source hover is routed to onHoverEvent FIRST — and because this view is
    // not hoverable, onHoverEvent returns false and the platform then delivers the SAME
    // MotionEvent to onGenericMotionEvent. Handle both entries so no hardware path can
    // hide the pen approach, but process a pointer-source hover only on the
    // onHoverEvent leg — otherwise every hover sample is handled (and dispatched to the
    // host's raw listener) twice.

    override fun onHoverEvent(event: MotionEvent): Boolean {
        handleStylusHover(event)
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isPointerSourceHover(event)) handleStylusHover(event)
        return super.onGenericMotionEvent(event)
    }

    /** True for hover actions from a pointer-source device — events [onHoverEvent] has
     *  already seen when they reach [onGenericMotionEvent]. Device subclasses adding
     *  their own per-event work to both entries use the same predicate to run it
     *  exactly once per sample. */
    protected fun isPointerSourceHover(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) &&
            (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT)

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
            StrokeRenderer.draw(
                canvas, activePoints, penColor, penWidth, penStyle, scratchPaint,
                pendingStrokeId().hashCode(),
            )
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
            // Guides first, under the content they aligned — a rule the ink crosses reads
            // as a line on the page; one laid over the ink reads as a strike-through.
            for (guide in activeSnapGuides) {
                when (guide) {
                    is SnapGuide.Vertical ->
                        canvas.drawLine(guide.x, 0f, guide.x, height.toFloat(), snapGuidePaint)
                    is SnapGuide.Horizontal ->
                        canvas.drawLine(0f, guide.y, width.toFloat(), guide.y, snapGuidePaint)
                }
            }
            val save = canvas.save()
            canvas.translate(dragDx, dragDy)
            for (s in dragStrokes) {
                StrokeRenderer.draw(
                    canvas, s.points, s.color, s.width, s.style, scratchPaint, s.id.hashCode()
                )
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
        // Transform layer (0.1.27): the object live through its renderer (which reads the
        // geometry the host set from onTransformChanged), then the overlay chrome.
        transform?.let { t ->
            val drawn = t.renderer?.drawObject(canvas, t.contentId) ?: false
            if (!drawn) {
                val b = t.box.aabb()
                canvas.drawRect(b.left, b.top, b.right, b.bottom, selectionPaint)
            }
            transformOverlay.draw(canvas, t.box, selectionPaint)
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
        val hiddenContent = hiddenContentIds()
        for (renderer in contentRenderers) {
            if (renderer.layer == ContentLayer.BELOW_STROKES) {
                renderer.draw(canvas, hiddenContent)
            }
        }
        if (pageMode == PageMode.RASTER) {
            // Two blits where the stroke loop would run: the page images sit at the page
            // origin, over the paper and under the host's above-strokes content, exactly
            // where the baked strokes would have been. The ink goes on through DARKEN —
            // the darker of the two per channel — so the pair flattens with no top and no
            // bottom (see [flattenPaint]); a page with only one of them is one blit.
            graphiteRaster?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            inkRaster?.let { canvas.drawBitmap(it, 0f, 0f, flattenPaint) }
        } else {
            for (stroke in strokeList) {
                // Mid-drag, the selected strokes live in the translated drag layer instead.
                if (stroke.id in dragHiddenIds) continue
                StrokeRenderer.draw(
                    canvas, stroke.points, stroke.color, stroke.width, stroke.style, scratchPaint,
                    stroke.id.hashCode(),
                )
            }
        }
        for (renderer in contentRenderers) {
            if (renderer.layer == ContentLayer.ABOVE_STROKES) {
                renderer.draw(canvas, hiddenContent)
            }
        }
    }

    /** Content ids the committed record leaves to a live layer: a drag's, plus the
     *  object under transform (its whole mode is a live layer, not just its gestures). */
    private fun hiddenContentIds(): Set<String> {
        val t = transform ?: return dragHiddenContentIds
        return if (dragHiddenContentIds.isEmpty()) setOf(t.contentId) else dragHiddenContentIds + t.contentId
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

    /** Returns false when a recognizer consumed the stroke (see [commitCapturedStroke]). */
    private fun commitActiveStroke(): Boolean {
        if (activePoints.isEmpty()) return true
        val points = activePoints.toList()
        activePoints.clear()
        return commitCapturedStroke(points)
    }

    /**
     * Commit one captured polyline as a stroke with the armed pen config: add to the
     * model, re-record, and fire [PaperListener.onStrokeCommitted]. The single commit
     * path for this engine's own capture and for device subclasses feeding points from
     * their hardware pipelines (a single contact may legally commit several strokes —
     * the Onyx SDK can deliver more than one batch per contact).
     *
     * This is also the pen-gesture detection point: with a recognizer enabled and
     * [Tool.PEN] armed, a qualifying polyline is consumed as a smart lasso or scribble
     * erase instead ([tryConsumeGesture]) — nothing commits, no
     * [PaperListener.onStrokeCommitted], and the return is false so callers skip their
     * end-of-writing signals ([PaperListener.onPenLifted]). Mid-contact fragments (the
     * exclusion-rect splits in [appendDrawPoints]) pass [allowGestures] = false: a
     * partial polyline is not a completed gesture. Returns true whenever the normal
     * commit ran (or there was nothing to commit).
     */
    protected fun commitCapturedStroke(
        points: List<StrokePoint>,
        allowGestures: Boolean = true,
    ): Boolean {
        if (points.isEmpty()) return true
        if (allowGestures && tool == Tool.PEN &&
            (smartLassoEnabled || scribbleEraseEnabled) && tryConsumeGesture(points)
        ) {
            return false
        }
        val stroke = Stroke(
            id = takePendingStrokeId(),
            points = points,
            color = penColor,
            width = penWidth,
            style = penStyle,
        )
        if (pageMode == PageMode.RASTER) {
            // The mark lands as pixels and the object is let go. Same stroke, same id
            // (the grain is seeded from it, exactly as the live preview was), same
            // renderer — only what is kept differs. The host hears about it three times:
            // will-change before the pixels move (its before-image moment), committed
            // (its timestamps and counts, as in stroke mode), changed after — and the
            // two raster halves come once per run of the mark (0.1.33), not once per
            // mark, so a long diagonal costs its host the ink's area and not the page's.
            // Every will-change first: the host must hold the before-image of the whole
            // mark before any of it lands. One contact is one style, so every one of
            // those halves names the same layer (0.1.39).
            val layer = RasterLayer.of(stroke.style)
            val dirty = rasterDirtyAlong(stroke)
            for (r in dirty) paperListener?.onRasterWillChange(layer, r)
            compositeIntoRaster(listOf(stroke))
            bakeAfterCommit()
            paperListener?.onStrokeCommitted(stroke)
            for (r in dirty) paperListener?.onRasterChanged(layer, r)
            return true
        }
        strokeList.add(stroke)
        modelChanged()
        bakeAfterCommit()
        paperListener?.onStrokeCommitted(stroke)
        return true
    }

    /** The id the in-progress stroke will carry, minting one if nothing has asked yet. */
    private fun pendingStrokeId(): String =
        pendingStrokeId ?: UUID.randomUUID().toString().also { pendingStrokeId = it }

    /** The pending id, consumed — the next stroke starts fresh. */
    private fun takePendingStrokeId(): String = pendingStrokeId().also { pendingStrokeId = null }

    // ── Pen-gesture recognizers (smart lasso / scribble erase) ───────────────

    /**
     * Run the enabled recognizers over a completed pen stroke. Returns true when the
     * stroke was consumed as a gesture; any candidate whose hit test comes up empty
     * falls through to ink — a gesture over blank paper stays writing.
     *
     * Recognition (and its hit tests) runs synchronously in the commit path; the
     * elapsed-time tripwire keeps that cost observable on slow hardware.
     */
    private fun tryConsumeGesture(points: List<StrokePoint>): Boolean {
        val startedMs = SystemClock.uptimeMillis()
        val consumed = recognizeGesture(points)
        val elapsedMs = SystemClock.uptimeMillis() - startedMs
        if (elapsedMs > GESTURE_RECOGNITION_BUDGET_MS) {
            Log.i(
                TAG,
                "gesture recognition took ${elapsedMs}ms " +
                    "(${points.size} gesture pts, ${strokeList.size} strokes, consumed=$consumed)",
            )
        }
        return consumed
    }

    /**
     * Shape classification runs FIRST and is exclusive: a **scribble-shaped** stroke
     * (dense oscillation — the [GestureRecognizer.isScribbleCandidate] gates) is an
     * erase intent and is never treated as a smart lasso, even when scribble erase
     * itself is disabled. Real zigzag scribbles routinely satisfy the loop gates too —
     * they end near their start and their curled turnarounds accumulate winding
     * (measured on the Supernote Nomad: scribbles were selecting instead of erasing) —
     * while a genuine selection loop is a smooth single pass that never reads
     * scribble-shaped. Everything not scribble-shaped may be a smart lasso.
     */
    private fun recognizeGesture(points: List<StrokePoint>): Boolean {
        val density = resources.displayMetrics.density
        val scribbleShaped = GestureRecognizer.isScribbleCandidate(
            points, GestureRecognizer.SCRIBBLE_MIN_DIAGONAL_DP * density,
        )
        if (scribbleShaped) {
            if (!scribbleEraseEnabled) return false
            val hitIds = EraseHitTest.hitStrokeIds(
                strokeList, points, GestureRecognizer.SCRIBBLE_STROKE_TOUCH_RADIUS_DP * density,
            )
            // Host content is crossed out too (0.1.23), on the stricter penetration rule —
            // a scribble is a large gesture, so the eraser's touch-anything rule would take
            // a heading every time the ink beside it was scribbled out.
            val contentHits = if (contentRenderers.isEmpty()) emptyList() else {
                EraseHitTest.scribbleContentIds(
                    contentRenderers.flatMap { r -> r.hitTargets().map { it.contentId to it.bounds } },
                    points,
                    GestureRecognizer.SCRIBBLE_BBOX_PENETRATION_DP * density,
                )
            }
            if (hitIds.isEmpty() && contentHits.isEmpty()) {
                Log.i(TAG, "scribble candidate touched nothing — committed as ink")
                return false
            }
            val idSet = hitIds.toHashSet()
            // Parity with eraseAlong: a host-injected selection losing a stroke or a
            // content object no longer describes reality.
            val sel = selection
            if (sel != null &&
                (sel.strokeIds.any { it in idSet } || sel.contentIds.any { it in contentHits })
            ) {
                clearSelection()
            }
            if (hitIds.isNotEmpty()) {
                strokeList.removeAll { it.id in idSet }
                modelChanged()
            }
            // One gesture, one callback — the host has to be able to record one undo entry
            // even when the scribble took ink and content together. The content is still on
            // the committed layer at this point; the host removes it and calls
            // notifyContentChanged, exactly as it does for the eraser tool.
            paperListener?.onScribbleErased(hitIds, contentHits)
            finalizeEraseRedraw()
            onGestureStrokeConsumed()
            Log.i(
                TAG,
                "scribble erase consumed ${hitIds.size} strokes, " +
                    "${contentHits.size} content objects",
            )
            return true
        }
        if (smartLassoEnabled &&
            GestureRecognizer.isSmartLassoCandidate(
                points, GestureRecognizer.SMART_LASSO_CLOSURE_DISTANCE_DP * density,
            )
        ) {
            val sel = buildSelectionFromOutline(points)
            if (sel == null) {
                Log.i(TAG, "smart-lasso candidate enclosed nothing — committed as ink")
                return false
            }
            // A live selection here can only be host-injected (setSelection while in
            // PEN) — dismiss it first so the host's callbacks pair up.
            if (selection != null) clearSelection()
            // Switch tool BEFORE creating the selection so the device engines run
            // their proven tool-boundary handoffs; the setter clears the session
            // flag, so set it after, then announce the component-initiated change.
            tool = Tool.LASSO
            smartLassoSession = true
            paperListener?.onToolChanged(Tool.LASSO)
            selection = sel
            invalidate()
            paperListener?.onSelectionCreated(sel)
            onGestureStrokeConsumed()
            Log.i(
                TAG,
                "smart lasso consumed: ${sel.strokeIds.size} strokes, " +
                    "${sel.contentIds.size} content objects",
            )
            return true
        }
        return false
    }

    /**
     * A recognizer consumed the just-captured stroke: its live ink must leave the
     * screen even though nothing committed. The base repaints (its live layer is
     * already empty); EPD engines override to retract their hardware overlay ink —
     * Onyx render-off + `handwritingRepaint` at contact end (withheld-frame rules),
     * Ratta the gesture-trace clear ladder.
     */
    protected open fun onGestureStrokeConsumed() {
        invalidate()
    }

    /**
     * Close out a smart-lasso session whose selection lifecycle has fully ended — no
     * active selection and no successor outline in flight — by restoring [Tool.PEN].
     * Wired into every dismissal/cancel exit in the base; device engines whose
     * pipelines cancel lasso gestures on their own (the Onyx raw path) call it from
     * those exits too. No-op outside a session.
     */
    protected fun maybeEndSmartLassoSession() {
        if (!smartLassoSession || suppressSmartLassoRestore) return
        if (selection != null) return
        smartLassoSession = false
        if (tool == Tool.LASSO) {
            tool = Tool.PEN
            // The restore is component-initiated and can land AFTER the dismissal
            // callback (a pen tap-away dismisses at pen-down, restores at pen-up) —
            // onToolChanged is the host's reliable signal, not re-reading [tool].
            paperListener?.onToolChanged(Tool.PEN)
        }
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
                // Mid-contact fragment — never a completed gesture (allowGestures off).
                if (activePoints.size >= 2) {
                    commitCapturedStroke(activePoints.toList(), allowGestures = false)
                }
                activePoints.clear()
            } else {
                activePoints.add(p)
            }
        }
    }

    /** Start a fresh eraser sweep: the next [eraseAlong] batch won't chain to the last. */
    protected fun beginEraseSweep() {
        lastEraserPoint = null
        rasterErasePending = null
        dropRubPass()
        rubDirection = null
    }

    /** Forget the rubbing pass in progress: the next batch lifts on top of what is there. */
    private fun dropRubPass() {
        val rect = rubPassRect ?: return
        rubPass?.let { RasterRub.clearPass(it, rubPassWidth, rect.left, rect.top, rect.width(), rect.height()) }
        rubPassRect = null
    }

    /** The row length the pass mask was allocated for — the page's width at the time. */
    private var rubPassWidth = 0

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
        endActiveTransform()
        if (points.isEmpty()) return
        val prev = lastEraserPoint
        if (prev == null) reportedContentErases.clear() // fresh sweep = fresh gesture dedup
        val sweep = prev?.let { ArrayList<StrokePoint>(points.size + 1).apply {
            add(it)
            addAll(points)
        } } ?: points
        lastEraserPoint = points.last()
        if (pageMode == PageMode.RASTER) {
            eraseRasterAlong(sweep)
            return
        }
        val hitIds = EraseHitTest.hitStrokeIds(strokeList, sweep, eraserRadius)
        // Host content is erased whole (0.1.4): report ids, the host removes and repaints.
        val contentHits = if (contentRenderers.isEmpty()) emptyList() else {
            val targets = contentRenderers.flatMap { r ->
                r.hitTargets().map { it.contentId to it.bounds }
            }.filter { (id, _) -> id !in reportedContentErases }
            EraseHitTest.hitContentIds(targets, sweep, eraserRadius)
        }
        if (hitIds.isEmpty() && contentHits.isEmpty()) return
        // A barrel-button erase can run while a selection is active (lasso mode): if it
        // takes any selected stroke or content object, the box no longer describes
        // reality — dismiss.
        val idSet = hitIds.toHashSet()
        val sel = selection
        if (sel != null &&
            (sel.strokeIds.any { it in idSet } || sel.contentIds.any { it in contentHits })
        ) {
            clearSelection()
        }
        if (hitIds.isNotEmpty()) {
            strokeList.removeAll { it.id in idSet }
            modelChanged()
            paperListener?.onStrokesErased(hitIds)
            throttledEraseRedraw()
        }
        if (contentHits.isNotEmpty()) {
            reportedContentErases.addAll(contentHits)
            paperListener?.onContentErased(contentHits)
        }
    }

    /**
     * Rub one batch of the sweep into a raster page (0.1.30; a hole-cutter from 0.1.26).
     * There is nothing to hit-test: the batch's pixels are read out of the page image,
     * lifted by [RasterRub.rubBatch] according to [rasterRubbing] and the batch's mean
     * pressure, and written back. The host hears about it exactly as it hears about a
     * mark — will-change with the batch rect before the pixels move (its before-image
     * moment, one tile per batch accumulating into one undo entry), changed after — and
     * nothing else fires: there are no ids for `onStrokesErased` to carry. Host content
     * renderers are not consulted either; on a raster page the eraser is a rubber, not a
     * tool that removes objects, and the reference engines' content erase stays a
     * stroke-mode feature.
     *
     * A reversal of travel ends the pass first, so rubbing back and forth lifts again on
     * each stroke of the arm; within a pass the seams between batches lift nothing twice.
     * The pass mask is allocated once per page size and cleared over only what a pass
     * touched — a page-sized zeroing per contact would be five megabytes for nothing.
     *
     * A page that has never been drawn on has no image, and rubbing it is nothing.
     *
     * **The rubber rubs graphite and only graphite (0.1.39).** The ink image is never
     * read, never allocated and never announced here — that is the whole of the artist's
     * rule ("in the real world, ink is more permanent than pencil") in the one place it
     * has to hold, and it is a property of which bitmap this method names rather than a
     * test performed on pixels. Whether a firm rub should lift ink *a little* is a
     * decision nobody has made; until someone does, it lifts none.
     */
    private fun eraseRasterAlong(sweep: List<StrokePoint>) {
        val target = graphiteRaster ?: return
        val dirty = RasterErase.batchRect(sweep, eraserRadius, target.width, target.height)
            ?.toRectOut() ?: return
        val pass = rubPassFor(target.width, target.height)
        RasterRub.direction(sweep)?.let { next ->
            if (RasterRub.isReversal(rubDirection, next)) dropRubPass()
            rubDirection = next
        }
        paperListener?.onRasterWillChange(RasterLayer.GRAPHITE, dirty)
        val w = dirty.width()
        val h = dirty.height()
        if (rubPixels.size < w * h) rubPixels = IntArray(w * h)
        target.getPixels(rubPixels, 0, w, dirty.left, dirty.top, w, h)
        var pressure = 0f
        for (p in sweep) pressure += p.pressure
        pressure /= sweep.size
        val changed = RasterRub.rubBatch(
            pixels = rubPixels, left = dirty.left, top = dirty.top, width = w, height = h,
            pageWidth = target.width, pass = pass, sweep = sweep, radius = eraserRadius,
            rubbing = rasterRubbing, lift = RasterRub.lift(pressure, rasterRubbing),
        )
        if (changed) target.setPixels(rubPixels, 0, w, dirty.left, dirty.top, w, h)
        rubPassRect = rubPassRect?.apply { union(dirty) } ?: Rect(dirty)
        paperListener?.onRasterChanged(RasterLayer.GRAPHITE, dirty)
        rasterErasePending = rasterErasePending?.apply { union(dirty) } ?: Rect(dirty)
        throttledEraseRedraw()
    }

    /** The pass mask for a page of this size, made fresh if the page changed shape. */
    private fun rubPassFor(width: Int, height: Int): ByteArray {
        val existing = rubPass
        if (existing != null && rubPassWidth == width && existing.size == width * height) return existing
        rubPassRect = null
        rubPassWidth = width
        return ByteArray(width * height).also { rubPass = it }
    }

    /**
     * How often the raster eraser may redraw mid-sweep, in milliseconds — the
     * per-engine cadence seam (0.1.32). [RASTER_ERASE_REDRAW_END_ONLY] means never:
     * the sweep is presented once, at its end.
     *
     * The seam exists because a mid-rub redraw costs a different thing on every
     * panel, and the right number is a measurement, not a constant. On Onyx the raw
     * pipeline withholds ordinary frames while the pen is down, so the engine asks
     * the panel for the changed region itself ([presentRasterEraseProgress]) and one
     * frame's cadence buys exactly that region. On Supernote there is no
     * regional-refresh transaction to ask for: every mid-sweep redraw is a whole app
     * frame the ink daemon must reconcile against the frozen pixels under its
     * overlay, and that cost grows with the unbaked ink already on it (the
     * frame-silence rule). So the cadence that reads as live rubbing on one panel is
     * the cadence that lags the hand on another, and each engine carries its own.
     *
     * Onyx and the generic engine keep [RASTER_ERASE_REDRAW_INTERVAL_MS], unchanged.
     * Ratta measured its way to the same 16 ms for a different reason (the frame-silence
     * cost is the overlay's masking, and an erase contact has already released the
     * overlay) — **agreeing on the number is not the same as sharing the reason**, which
     * is why the seam stays rather than collapsing back into a constant.
     */
    protected open val rasterEraseRedrawIntervalMs: Long get() = RASTER_ERASE_REDRAW_INTERVAL_MS

    private fun throttledEraseRedraw() {
        val interval = if (pageMode == PageMode.RASTER) rasterEraseRedrawIntervalMs
                       else ERASE_REDRAW_INTERVAL_MS
        // End-only: nothing is presented until finalizeEraseRedraw, which redraws the
        // committed layer whether or not a mid-sweep redraw ever ran. The pending
        // union goes on accumulating and is dropped there, exactly as it is when the
        // last batch of an ordinary sweep falls inside the throttle window.
        if (interval == RASTER_ERASE_REDRAW_END_ONLY) return
        val now = SystemClock.uptimeMillis()
        if (now - lastEraseRedrawMs >= interval) {
            lastEraseRedrawMs = now
            redrawCommitted()
            rasterErasePending?.let {
                rasterErasePending = null
                presentRasterEraseProgress(it)
            }
        }
    }

    /**
     * A raster erase has just been redrawn into the committed layer mid-sweep, and
     * [rect] (view space — the page image sits at the view origin) is what changed
     * since the panel last saw it. The base engine does nothing beyond the
     * `invalidate` already issued: on an ordinary display that frame simply presents.
     * An e-ink engine whose panel withholds ordinary frames while the pen is in
     * contact overrides this to ask the panel for the region itself, so the artist
     * sees graphite lifting under the rubber rather than all at once at pen-up —
     * which is what rubbing feels like, and the whole reason the raster page exists.
     * Never called at sweep end; [finalizeEraseRedraw] and the engine's own end-of-sweep
     * repaint cover that.
     */
    protected open fun presentRasterEraseProgress(rect: Rect) {}

    /** Flush any throttled removals at gesture end so the screen is exact on pen lift. */
    protected fun finalizeEraseRedraw() {
        lastEraseRedrawMs = SystemClock.uptimeMillis()
        rasterErasePending = null
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
    protected val isSelectionDragActive: Boolean get() = dragActive || isTransformGestureActive

    /** Whether a selection is currently active — for device subclasses deciding whether
     *  a gesture's outcome changed the overlay chrome (e.g. a tap that dismissed). */
    protected val hasActiveSelection: Boolean get() = selection != null || transform != null

    /** Whether ([x], [y]) falls inside the active selection's drawn box (the inflated
     *  [Selection.bounds]) — the pre-contact test device subclasses run from their hover
     *  stream (Ratta's law-3 drag suppress). False when nothing is selected. */
    protected fun selectionBoxContains(x: Float, y: Float): Boolean {
        // In transform mode the grab region — box, handles, knob — is the "box": a
        // contact there is a transform gesture and must get the drag treatment (the
        // Ratta firmware trail suppress), a contact outside it ends the mode.
        transform?.let { t -> return classifyTransformGrab(t, x, y) != TransformGrab.NONE }
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
        transform?.let { t ->
            // Transform mode owns every contact inside its grab region; a contact outside
            // it falls through to the outline path, whose lassoOutlineStart ends the mode.
            if (isTransformGestureActive) return false
            val grab = classifyTransformGrab(t, x, y)
            if (grab == TransformGrab.NONE) return false
            beginTransformGesture(t, grab, x, y)
            return true
        }
        if (!selectionBoxContains(x, y)) return false
        val sel = selection ?: return false
        dragActive = true
        dragThresholdMet = false
        dragStartX = x
        dragStartY = y
        dragDx = 0f
        dragDy = 0f
        dragStrokes = strokeList.filter { it.id in sel.strokeIds }
        // One hitTargets() pass per renderer, split two ways: the selected objects ride the
        // drag layer, and — when snapping is armed — the rest become its guides. A host's
        // hitTargets() is arbitrary work, so it is asked once, not once per purpose.
        val travelling = ArrayList<Pair<ContentRenderer, HitTarget>>()
        val staying = ArrayList<Bounds>()
        for (renderer in contentRenderers) {
            for (target in renderer.hitTargets()) {
                if (target.contentId in sel.contentIds) {
                    travelling.add(renderer to target)
                } else if (snapToGuides) {
                    // The page's fixed points. Strokes are deliberately not among them: on a
                    // handwriting page ink is everywhere, and a guide per stroke box would be
                    // a thicket that fights the pen rather than helping it.
                    staying.add(target.bounds)
                }
            }
        }
        dragContentTargets = travelling
        snapTargets = staying
        return true
    }

    /**
     * Drag-move sample. Once the pen travels [DRAG_THRESHOLD_DP] from the contact start
     * this fires [PaperListener.onSelectionDragStarted], hides the selected strokes from
     * the committed record, and starts the translated drag layer; below it the contact
     * is still a potential tap.
     */
    protected fun lassoDragMove(x: Float, y: Float) {
        if (isTransformGestureActive) {
            transformGestureMove(x, y)
            return
        }
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
        applyDragDelta(dx, dy)
        throttledLassoInvalidate()
    }

    /**
     * Turn a raw contact delta into the delta actually applied, updating [dragDx]/[dragDy]
     * and [activeSnapGuides]. Pass-through unless [snapToGuides] is armed and a selection
     * is live.
     *
     * Both the move samples and the lift go through here, so the drop can never disagree
     * with what the drag was doing a moment earlier.
     */
    private fun applyDragDelta(rawDx: Float, rawDy: Float) {
        val sel = selection
        if (!snapToGuides || sel == null) {
            dragDx = rawDx
            dragDy = rawDy
            activeSnapGuides = emptyList()
            return
        }
        val page = templateDestRect()
        val snap = SnapEngine.computeSnap(
            box = sel.bounds,
            rawDx = rawDx,
            rawDy = rawDy,
            pageWidth = page.width(),
            pageHeight = page.height(),
            marginPx = snapMarginPx,
            thresholdPx = SNAP_THRESHOLD_DP * resources.displayMetrics.density,
            targets = snapTargets,
        )
        dragDx = snap.dx
        dragDy = snap.dy
        activeSnapGuides = snap.guides
    }

    /**
     * Pen-up on a drag contact. Below the threshold it was a tap inside the box: the
     * selection stays put and [PaperListener.onSelectionTapped] fires — at once for the
     * stylus, after the pen-gate escrow for a finger ([fromFinger]; see
     * [scheduleEscrowedTap]). Past it: translate the selected strokes in the model,
     * restore the committed record, move the box, and report
     * [PaperListener.onSelectionMoved] — the selection remains active at its new position.
     */
    protected fun lassoDragFinish(x: Float, y: Float, fromFinger: Boolean = false) {
        if (isTransformGestureActive) {
            transformGestureFinish(x, y)
            return
        }
        if (!dragActive) return
        dragActive = false
        if (!dragThresholdMet) {
            dragStrokes = emptyList()
            dragContentTargets = emptyList()
            snapTargets = emptyList()
            activeSnapGuides = emptyList()
            if (selection != null) {
                if (fromFinger) scheduleEscrowedTap(x, y) else paperListener?.onSelectionTapped(x, y)
            }
            return
        }
        dragThresholdMet = false
        // Settle on the lift position — freshest, and a fast drag can travel a real
        // distance between the last move sample and the lift — but through the SAME snap
        // pass the samples took. Reading `x - dragStartX` raw here would silently undo the
        // snap the user just watched catch.
        applyDragDelta(x - dragStartX, y - dragStartY)
        val dx = dragDx
        val dy = dragDy
        dragDx = 0f
        dragDy = 0f
        dragStrokes = emptyList()
        dragContentTargets = emptyList()
        snapTargets = emptyList()
        activeSnapGuides = emptyList()
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
        if (isTransformGestureActive) transformGestureCancel()
        if (dragActive) clearSelection()
    }

    /** Pen-down starting a NEW outline: dismiss any active selection immediately, so the
     *  user sees the box drop the moment they start lassoing elsewhere (and a tap-sized
     *  gesture outside the box needs nothing more — tap-to-dismiss falls out). */
    protected fun lassoOutlineStart() {
        // A contact that dismisses is spent on the dismissal — completeLassoOutline must
        // not also read it as an empty-handed tap (0.1.5).
        // Likewise a contact that ends transform mode (0.1.27) — outside the grab region.
        val endedTransform = endActiveTransform()
        outlineDismissedSelection = selection != null || endedTransform
        // The dismissal belongs to a NEW outline — a smart-lasso session continues
        // into it; the outline's own exits decide whether to restore PEN.
        suppressSmartLassoRestore = true
        try {
            clearSelection()
        } finally {
            suppressSmartLassoRestore = false
        }
    }

    /**
     * A completed lasso outline (from either the base MotionEvent path or a device
     * pipeline's own capture): classify tap vs outline by gesture extent, hit-test the
     * strokes ([LassoHitTest]) and host content ([ContentRenderer.hitTargets]), and
     * create + report the selection. An empty catch leaves nothing selected (and ends
     * a smart-lasso session — the pen restores).
     */
    protected fun completeLassoOutline(outline: List<StrokePoint>) {
        // Repaint regardless of outcome — the base-drawn trail must leave the screen.
        invalidate()
        if (tool == Tool.LASSO_ERASER) {
            outlineDismissedSelection = false
            completeLassoErase(outline)
            return
        }
        val dismissed = outlineDismissedSelection
        outlineDismissedSelection = false
        val threshold = dragThresholdPx()
        val extent = Bounds.of(outline)
        // Below the extent threshold it was a tap: the previous selection was already
        // dismissed at outline start.
        val isTap = extent.width < threshold && extent.height < threshold
        if (!isTap && outline.size >= 3) {
            val sel = buildSelectionFromOutline(outline)
            if (sel != null) {
                selection = sel
                invalidate()
                paperListener?.onSelectionCreated(sel)
            }
        } else if (isTap && !dismissed && outline.isNotEmpty()) {
            // An empty-handed tap on bare paper (0.1.5). Not the tap that dismissed a
            // selection — that contact is spent. Reported at the pen-up point. A
            // few-sample gesture with a real extent is neither an outline nor a tap and
            // still reports nothing.
            val last = outline.last()
            paperListener?.onPaperTapped(last.x, last.y)
        }
        maybeEndSmartLassoSession()
    }

    /**
     * A completed [Tool.LASSO_ERASER] outline (0.1.28): the lasso's own hit test, then the
     * scribble-erase consume recipe — the hit strokes leave the model, the whole gesture is
     * reported once through [PaperListener.onLassoErased], and the committed layer is
     * re-recorded here (the host must not repaint). A tap-sized contact, a degenerate
     * outline, or a loop that takes nothing reports nothing at all — there is no
     * paste-here tap in this tool, that hook is the lasso's.
     */
    private fun completeLassoErase(outline: List<StrokePoint>) {
        val threshold = dragThresholdPx()
        val extent = Bounds.of(outline)
        if (extent.width < threshold && extent.height < threshold) return
        if (outline.size < 3) return
        val (hitIds, contentTargets) = outlineHits(outline)
        if (hitIds.isEmpty() && contentTargets.isEmpty()) return
        val idSet = hitIds.toHashSet()
        val contentHits = contentTargets.map { it.contentId }
        // Parity with eraseAlong: a host-injected selection losing a member no longer
        // describes reality. (The tool setter already dropped any selection — kept so a
        // setSelection issued while armed still pairs its callbacks up.)
        val sel = selection
        if (sel != null &&
            (sel.strokeIds.any { it in idSet } || sel.contentIds.any { it in contentHits })
        ) {
            clearSelection()
        }
        if (hitIds.isNotEmpty()) {
            strokeList.removeAll { it.id in idSet }
            modelChanged()
        }
        // One gesture, one callback. The content is still on the committed layer at this
        // point; the host removes it and calls notifyContentChanged, as for the eraser tool.
        paperListener?.onLassoErased(hitIds, contentHits)
        finalizeEraseRedraw()
        onGestureStrokeConsumed()
        Log.i(TAG, "lasso erase took ${hitIds.size} strokes, ${contentHits.size} content objects")
    }

    /** The lasso's hit rule over a closed outline: strokes with any point inside, host
     *  hit targets the outline touches. Shared by the selection builder and the lasso
     *  eraser so the two can never disagree. */
    private fun outlineHits(outline: List<StrokePoint>): Pair<List<String>, List<HitTarget>> {
        val strokeIds = LassoHitTest.hitStrokeIds(strokeList, outline)
        val contentTargets = contentRenderers.flatMap { it.hitTargets() }
            .filter { LassoHitTest.polygonIntersectsBounds(outline, it.bounds) }
        return strokeIds to contentTargets
    }

    /** Hit-test a closed outline against the strokes and the host-content hit targets
     *  and build the [Selection] — shared by [completeLassoOutline] and the smart-lasso
     *  recognizer. Null when the outline encloses nothing. */
    private fun buildSelectionFromOutline(outline: List<StrokePoint>): Selection? {
        val (strokeIds, contentTargets) = outlineHits(outline)
        if (strokeIds.isEmpty() && contentTargets.isEmpty()) return null
        val idSet = strokeIds.toHashSet()
        var bounds: Bounds? = null
        for (s in strokeList) {
            if (s.id in idSet) bounds = bounds?.union(s.bounds) ?: s.bounds
        }
        for (t in contentTargets) bounds = bounds?.union(t.bounds) ?: t.bounds
        return Selection(
            strokeIds = idSet,
            contentIds = contentTargets.mapTo(HashSet()) { it.contentId },
            bounds = bounds ?: return null,
        )
    }

    /**
     * A threshold-crossed drag-move became (true) / stopped being (false) the live
     * visual: device engines hook their display-pipeline tuning here — the Onyx engine
     * runs the drag in the EPD's A2 fast mode. Always balanced; called before the
     * end-of-drag repaint so the restored mode covers the final quality frame.
     */
    protected open fun onSelectionDragVisual(active: Boolean) {}

    // ── Transform mode (0.1.27) ──────────────────────────────────────────────

    override fun beginTransform(contentId: String, box: OrientedBox, aspectLocked: Boolean, minSizePx: Float) {
        if (released || tool != Tool.LASSO) return
        endActiveTransform()
        // Host-initiated: the selection goes without its dismissed callback (the
        // setSelection rule) — a live drag, though, must still unwind through clearSelection.
        if (dragActive) clearSelection()
        selection = null
        val renderer = contentRenderers.firstOrNull { r -> r.hitTargets().any { it.contentId == contentId } }
        transform = TransformState(contentId, box, box, aspectLocked, minSizePx, renderer)
        // The committed record drops the object; the transform layer draws it from here.
        redrawCommitted()
    }

    override fun endTransform() {
        endActiveTransform()
    }

    override fun setTransformAspectLocked(locked: Boolean) {
        transform?.aspectLocked = locked
    }

    override val transformingContentId: String? get() = transform?.contentId

    override val transformBox: OrientedBox? get() = transform?.box

    /** Whether a transform contact (handle, knob or body) is in flight. */
    private val isTransformGestureActive: Boolean get() = transform?.grab?.let { it != TransformGrab.NONE } ?: false

    /**
     * Leave transform mode from any exit. A gesture in flight is cancelled first (the
     * contact's box is dropped, the one from before it stands). Restores the object to
     * the committed record, then reports — the listener sees the page already right.
     * Returns whether a mode was active.
     */
    private fun endActiveTransform(): Boolean {
        val t = transform ?: return false
        if (t.grab != TransformGrab.NONE) transformGestureCancel()
        transform = null
        redrawCommitted()
        paperListener?.onTransformEnded(t.contentId, t.before, t.box)
        return true
    }

    private fun classifyTransformGrab(t: TransformState, x: Float, y: Float): TransformGrab =
        TransformGeometry.classify(
            t.box, x, y,
            transformOverlay.handleTouchRadius, transformOverlay.knobOffset, transformOverlay.knobTouchRadius,
        )

    private fun beginTransformGesture(t: TransformState, grab: TransformGrab, x: Float, y: Float) {
        t.grab = grab
        t.gestureStart = t.box
        t.startX = x
        t.startY = y
        onSelectionDragVisual(true)
    }

    /** The box the gesture in flight puts at the page point — from the gesture's start
     *  box and this point alone. */
    private fun transformBoxAt(t: TransformState, x: Float, y: Float): OrientedBox = when (t.grab) {
        TransformGrab.BODY -> TransformGeometry.move(t.gestureStart, x - t.startX, y - t.startY)
        TransformGrab.ROTATE -> TransformGeometry.rotate(t.gestureStart, x, y)
        TransformGrab.NONE -> t.box
        else -> TransformGeometry.resize(t.gestureStart, t.grab, x, y, t.aspectLocked, t.minSizePx)
    }

    private fun transformGestureMove(x: Float, y: Float) {
        val t = transform ?: return
        val next = transformBoxAt(t, x, y)
        if (next == t.box) return
        t.box = next
        val now = SystemClock.uptimeMillis()
        if (now - lastTransformReportMs >= LASSO_REFRESH_INTERVAL_MS) {
            lastTransformReportMs = now
            reportTransformChanged(t)
            invalidate()
        }
    }

    private fun transformGestureFinish(x: Float, y: Float) {
        val t = transform ?: return
        t.box = transformBoxAt(t, x, y)
        t.grab = TransformGrab.NONE
        onSelectionDragVisual(false)
        reportTransformChanged(t)
        invalidate()
    }

    /** ACTION_CANCEL, barrel erase, a data-in call mid-contact: the contact never
     *  happened — the box returns to where the gesture began. */
    private fun transformGestureCancel() {
        val t = transform ?: return
        t.box = t.gestureStart
        t.grab = TransformGrab.NONE
        onSelectionDragVisual(false)
        reportTransformChanged(t)
        invalidate()
    }

    /** Hand the live box to the host once per change — never the same box twice. */
    private fun reportTransformChanged(t: TransformState) {
        if (t.box == t.reported) return
        t.reported = t.box
        paperListener?.onTransformChanged(t.contentId, t.box)
    }

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
                if (tool != Tool.LASSO || (selection == null && transform == null) || isPenActive) return false
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
                    if (isPenActive) lassoDragCancel() else lassoDragFinish(event.x, event.y, fromFinger = true)
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
        transform?.let { t ->
            postDelayed({
                if (!released && !isPenActive && transform === t) endActiveTransform()
            }, PaperView.PEN_ACTIVE_TAIL_MS)
            return
        }
        val sel = selection ?: return
        postDelayed({
            if (!released && !isPenActive && selection === sel) clearSelection()
        }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /** Commit a finger tap-inside-the-box after the same pen-gate escrow as the dismissal
     *  (0.1.1): dropped if the pen became active meanwhile (it was a palm) or the selection
     *  already changed. */
    private fun scheduleEscrowedTap(x: Float, y: Float) {
        val sel = selection ?: return
        postDelayed({
            if (!released && !isPenActive && selection === sel) paperListener?.onSelectionTapped(x, y)
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
        maybeEndSmartLassoSession()
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
