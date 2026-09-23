package com.symmetricalpalmtree.gpaper.ratta

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Supernote panel driver, opened directly (Phase 28) — what lets the pencil preview in
 * sixteen greys with no daemon, no bake and no flash.
 *
 * `/dev/ebc` carries the vendor's `rga_device` label and the vendor policy line
 * `allow appdomain rga_device chr_file { open ioctl map … }`, so an ordinary
 * `untrusted_app` may open it; that was not assumed but proved, from a from-scratch probe
 * app on both a Nomad and a Manta (`probe-ebc/`, whose README holds every number below).
 * Atelier — Supernote's own drawing app — paints the same way, which is why its pencils
 * have never flashed.
 *
 * What this class owns: the fd, the geometry the driver reports, the mapping of **frame 0
 * only** (the compositor's output — frames 1–4 are the daemon's ink overlay, a shadow copy
 * and RGA scratch, none of them ours to keep), and one [HandlerThread] through which every
 * display call goes. Everything else — what grey a pixel should be, where the flecks are —
 * belongs to the caller.
 *
 * **Nothing here is fatal.** A refused open, a driver that answers nonsense, a missing
 * native library: each is one `Log.w` and [isOpen] stays false, and the pencil keeps the
 * firmware needle preview it has had since 0.1.32. A direct panel is an improvement on a
 * working path, never a prerequisite for one.
 *
 * ## The two rules the hardware imposes
 *
 * - **The ioctl blocks** — up to ~65 ms while a previous update is in flight (measured;
 *   the call itself averages 0.4–0.9 ms when it does not). It may not run on the UI thread
 *   at input rate, so it runs on [thread], and everything that piles up behind a blocked
 *   call is unioned into **one** rect rather than queued: the pen has moved on, and the
 *   union of two rects is a single correct update where two rects would be two waits.
 * - **The pixels are written before the rect is queued**, on the calling thread. The
 *   mapping is shared memory; the display call only says "show what is there now". Writing
 *   on the panel thread instead would put the hand's latest dab behind whatever wait is in
 *   progress, which is the one thing this whole path exists to remove.
 */
internal class EbcPanel {

    private companion object {
        const val TAG = "GPaperRatta"
        const val DEVICE = "/dev/ebc"

        /** `GETINFO`'s out-struct: `[1] = h<<16 | w`, `[3] = frameBytes`. Ample room. */
        const val INFO_BYTES = 4096

        /** Frame 0 — the compositor's output, the only frame this path writes. It sits at
         *  offset 0 of the mapping, which is why nothing here ever adds a frame offset. */
        const val FRAME = 0

        /** How many frames the driver keeps — the length the firmware's own `initEbc` maps,
         *  and this path's fallback if a shorter mapping is refused. */
        const val FRAMES = 5
    }

    /** Volatile because the display thread reads it while the UI thread may be closing:
     *  a stale fd there is an `EBADF` and a log line (the kernel keeps the file alive for
     *  the duration of a syscall already in flight), a torn read would be worse. */
    @Volatile
    private var fd = -1
    private var map: ByteBuffer? = null
    private var mapBytes = 0L
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Panel geometry as the driver reports it — see [EbcGeometry] for why it may differ
     *  from the screen's. */
    var panelW = 0
        private set
    var panelH = 0
        private set

    /** One frame's size in bytes: `panelW × panelH`, one byte per pixel. */
    var frameBytes = 0
        private set

    @Volatile
    private var open = false

    val isOpen: Boolean get() = open

    /** True once an attempt has been made, successful or not — the log line is once, not
     *  once per re-arm (every focus gain re-runs the firmware setup). */
    private var attempted = false

    /** The screen rects queued since the last display call, as one union. */
    private val pending = Rect()
    private var hasPending = false
    private var scheduled = false
    private val lock = Any()

    /** Panel-thread scratch: the 24-byte argument and the rect it is built from. */
    private val argBytes = ByteArray(EbcDisplayArg.SIZE)
    private val argBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(EbcDisplayArg.SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private val panelRect = IntArray(4)
    private val drainRect = Rect()

    private var rotated = false

    /**
     * Whether this panel's buffer is a quarter turn away from the screen — false until
     * [open] has read the geometry. Public because the *stylus* axes lie in the panel's
     * frame too, so the pencil's lean direction needs the same turn the pixels do
     * ([EbcGeometry.screenAzimuth], Phase 36).
     */
    val isRotated: Boolean get() = rotated

    /**
     * Open the driver, read the geometry, map frame 0 and start the display thread.
     * Idempotent; false (with one `Log.w`) when any step fails, and a failed attempt is
     * not retried — a driver that refused an app once will refuse it all session.
     */
    fun open(screenW: Int, screenH: Int): Boolean {
        if (open) return true
        if (attempted) return false
        attempted = true
        if (!EbcNative.available) return false
        val opened = EbcNative.open(DEVICE, EbcNative.O_RDWR or EbcNative.O_CLOEXEC)
        if (opened < 0) {
            Log.w(TAG, "panel: needle — $DEVICE refused (${EbcNative.strerror(-opened)})")
            return false
        }
        fd = opened
        val info = ByteBuffer.allocateDirect(INFO_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val got = EbcNative.ioctl(fd, EbcDisplayArg.REQ_GETINFO, info)
        if (got < 0) {
            Log.w(TAG, "panel: needle — GETINFO failed (${EbcNative.strerror(-got)})")
            return closeFd()
        }
        val wh = info.getInt(4)
        panelW = wh and 0xFFFF
        panelH = wh ushr 16
        frameBytes = info.getInt(12)
        // A driver answering nonsense is a driver we do not understand; walk away rather
        // than mmap a size from it and write pixels into whatever that turns out to be.
        if (panelW <= 0 || panelH <= 0 || frameBytes < panelW * panelH) {
            Log.w(TAG, "panel: needle — GETINFO implausible (${panelW}x$panelH, $frameBytes B)")
            return closeFd()
        }
        // Frame 0 alone is all this path writes. The probe mapped all five because it was
        // reading them; if a driver only honours the whole buffer (the length the firmware's
        // own initEbc asks for) take that instead and use its first frame — the same pixels,
        // at the same address, and the untested shorter call is not worth a dead pencil.
        mapBytes = frameBytes.toLong()
        var mapped = EbcNative.mmap(fd, mapBytes, 0L)
        if (mapped == null) {
            mapBytes = frameBytes.toLong() * FRAMES
            mapped = EbcNative.mmap(fd, mapBytes, 0L)
        }
        if (mapped == null) {
            Log.w(TAG, "panel: needle — mmap failed (${EbcNative.strerror(EbcNative.lastErrno())})")
            mapBytes = 0L
            return closeFd()
        }
        map = mapped
        rotated = EbcGeometry.isRotated(panelW, panelH, screenW, screenH)
        thread = HandlerThread("gpaper-ebc").also {
            it.start()
            handler = Handler(it.looper)
        }
        open = true
        Log.i(
            TAG,
            "panel: direct — ${panelW}x$panelH, ${frameBytes}B/frame, " +
                (if (rotated) "rotated" else "identity") + " against screen ${screenW}x$screenH",
        )
        return true
    }

    /**
     * Close the driver and stop the display thread. Idempotent.
     *
     * A session that worked may be opened again — a view detached and re-attached is an
     * ordinary thing for a host to do, and it must not cost the pencil its panel. A session
     * that was *refused* is not retried: a driver that turned this app away once will turn
     * it away all run, and retrying per focus gain would only fill the log.
     */
    fun close() {
        if (!open && fd < 0) return
        if (open) attempted = false
        open = false
        synchronized(lock) {
            hasPending = false
            scheduled = false
            pending.setEmpty()
        }
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        map?.let { EbcNative.munmap(it, mapBytes) }
        map = null
        closeFd()
        Log.i(TAG, "panel: closed")
    }

    private fun closeFd(): Boolean {
        if (fd >= 0) EbcNative.close(fd)
        fd = -1
        return false
    }

    /**
     * Show [screenRect] with the 4-bit [levels] in it — one byte per pixel, row-major,
     * [stride] bytes to the next row, in **screen** coordinates (the caller has added its
     * own on-screen offset).
     *
     * The pixels go into frame 0 here and now, rotated if this panel is; the rect is
     * unioned into whatever the display thread has not sent yet. Clipped to the panel: a
     * host whose view overhangs the screen still draws, it simply shows nothing off the
     * edge.
     *
     * [levels] is indexed from ([originLeft], [originTop]) — the rect's own corner unless
     * the caller says otherwise, which it does when posting one **piece** of a larger
     * rect's levels (a post cut around a chrome zone, [PanelClip]): the piece's rect is
     * smaller, the array is still the whole rect's.
     */
    fun post(
        screenRect: Rect,
        levels: ByteArray,
        stride: Int,
        originLeft: Int = screenRect.left,
        originTop: Int = screenRect.top,
    ) {
        val m = map ?: return
        if (!open) return
        val screenW = if (rotated) panelH else panelW
        val screenH = if (rotated) panelW else panelH
        val left = screenRect.left.coerceIn(0, screenW)
        val top = screenRect.top.coerceIn(0, screenH)
        val right = screenRect.right.coerceIn(0, screenW)
        val bottom = screenRect.bottom.coerceIn(0, screenH)
        if (right <= left || bottom <= top) return
        for (sy in top until bottom) {
            val row = (sy - originTop) * stride - originLeft
            for (sx in left until right) {
                m.put(EbcGeometry.panelIndex(rotated, panelW, panelH, sx, sy), levels[row + sx])
            }
        }
        synchronized(lock) {
            if (hasPending) pending.union(left, top, right, bottom)
            else {
                pending.set(left, top, right, bottom)
                hasPending = true
            }
            if (!scheduled) {
                scheduled = true
                handler?.post(drain)
            }
        }
    }

    /**
     * One display call for everything queued. Runs on [thread]; while it is blocked in the
     * ioctl the UI thread goes on writing pixels and unioning rects, and the moment it
     * returns a second drain is already queued with the union of them — never a backlog of
     * one call per batch, which at input rate would fall further behind the hand with every
     * sample.
     */
    private val drain = Runnable {
        val send: Boolean
        synchronized(lock) {
            send = hasPending
            if (send) drainRect.set(pending)
            hasPending = false
            scheduled = false
        }
        if (!send || !open) return@Runnable
        val f = fd
        if (f < 0) return@Runnable
        EbcGeometry.panelRect(
            rotated, panelH, drainRect.left, drainRect.top, drainRect.right, drainRect.bottom,
            panelRect,
        )
        EbcDisplayArg.pack(
            left = panelRect[0],
            top = panelRect[1],
            right = panelRect[2],
            bottom = panelRect[3],
            bufOffset = 0,
            mode = EbcDisplayArg.MODE_GREY16,
            flag = EbcDisplayArg.FLAG_ATELIER,
            into = argBytes,
        )
        argBuffer.rewind()
        argBuffer.put(argBytes)
        val ret = EbcNative.ioctl(f, EbcDisplayArg.REQ_DISPAREA, argBuffer)
        if (ret < 0) Log.w(TAG, "panel: DISPAREA failed (${EbcNative.strerror(-ret)})")
    }
}
