package com.symmetricalpalmtree.gpaper.probe

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The live-stroke experiment. A stylus contact lays round dabs, shaded by pressure, and every
 * MotionEvent does two things with the same pixels:
 *
 *  1. writes them into the driver's frame 0 (rotated to panel space) and sends one
 *     HTEINK_IOC_DISPAREA mode 7 for the dirty rect — the panel shows them at once, 16 greys,
 *     no bake;
 *  2. (unless --ez mirror false) draws them into this window's bitmap and invalidates the same
 *     rect, so when the compositor next rewrites frame 0 it writes the very same levels back —
 *     the recompose is a no-op on the panel and the stroke is permanent.
 *
 * The grey chosen for the mirror is one that the compositor maps to exactly the level we
 * painted (the table read back in the calibration run). Level 9 is never produced by the
 * compositor, so it is mirrored as 10.
 */
class DrawActivity : Activity() {
    private companion object {
        const val TAG = "EbcProbe"
        const val REQ_R1 = 0x48545201L
        const val REQ_DISPAREA = 0x48545701L
        const val FRAMES = 5
        const val MODE = 7
        /** Atelier's own display call: mode 7, frame 0, flag 1 (libspaint repaint::display_rect_after_set). */
        const val ATELIER_FLAG = 1
        const val RADIUS = 5
        /** One Android grey per 4-bit level, from the calibration table (level → compositor level is identity). */
        val MIRROR_GREY = intArrayOf(0, 81, 93, 103, 113, 125, 135, 145, 159, 177, 177, 191, 199, 209, 219, 255)
    }

    private var fd = -1
    private var map: ByteBuffer? = null
    private var frameBytes = 0L
    private var panelW = 0
    private var panelH = 0
    private lateinit var view: DrawView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        fd = Native.open("/dev/ebc", Native.O_RDWR or 0x80000)
        if (fd < 0) { Log.w(TAG, "draw: open failed ${Native.strerror(-fd)}"); finish(); return }
        val info = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
        Native.ioctl(fd, REQ_R1, info)
        panelW = info.getInt(4) and 0xFFFF; panelH = info.getInt(4) ushr 16
        frameBytes = info.getInt(12).toLong()
        map = Native.mmap(fd, frameBytes * FRAMES, 0)?.order(ByteOrder.LITTLE_ENDIAN)
        if (map == null) { Log.w(TAG, "draw: mmap failed"); finish(); return }
        view = DrawView(this, if (intent.getBooleanExtra("mirror", true)) Mirror.UP else Mirror.OFF)
        setContentView(view)
        Log.i(TAG, "draw: panel ${panelW}x${panelH}, mirror=${view.mirror}, flag=${view.flag}")
    }

    override fun onDestroy() {
        map?.let { Native.munmap(it, frameBytes * FRAMES) }
        if (fd >= 0) Native.close(fd)
        super.onDestroy()
    }

    /** When the window learns of the stroke: never · at pen-up (Atelier's way) · every event. */
    enum class Mirror { OFF, UP, LIVE }

    private inner class DrawView(c: Context, var mirror: Mirror) : View(c) {
        private val buttons = listOf("Refresh", "Clean", "Mode", "Dab", "Flag", "Mirror", "Clear", "Exit")
        /** EinkManager.screenRefresh(hide, n) variants, cycled per tap: (false,0) (false,1) (false,2) (false,3) (true,0) (true,1). */
        private val refreshVariants = listOf(false to 0, false to 1, false to 2, false to 3, true to 0, true to 1)
        private var refreshIndex = 0
        /** Idle clean pass: 1.5 s after the last pen-up, re-display everything drawn since the
         *  last clean in mode 4 (levels × 4) — a full-waveform drive of every pixel in the rect. */
        var cleanOn = intent.getBooleanExtra("clean", true)
        private val cleanRect = Rect()
        private val cleanRunnable = Runnable { cleanPass() }
        /** Display mode + the value scale it takes: mode 7 is 0..15, mode 4 is 0..60 (level × 4). */
        var mode = intent.getIntExtra("mode", MODE)
        private val scale get() = if (mode == 4) 4 else 1
        /** Solid: one grey level per dab, from pressure. Flecks: black-only 1 px flecks, density from pressure. */
        var flecks = intent.getBooleanExtra("flecks", false)
        private val rng = java.util.Random(7)
        /** Dabs are laid along the path at a fixed spacing, never one per sample: a fast hand outruns the digitizer. */
        private val spacing = RADIUS * 0.5f
        private var lastX = 0f; private var lastY = 0f; private var lastP = 0f; private var carry = 0f; private var down = false
        var flag = intent.getIntExtra("flag", ATELIER_FLAG)
        private val strokeRect = Rect()
        private var levels = ByteArray(0)          // screen-space truth, one 4-bit level per pixel, 15 = paper
        private var bitmap: Bitmap? = null
        private val paint = Paint()
        private val label = Paint().apply { color = Color.BLACK; textSize = 22f }
        private var samples = 0; private var events = 0; private var latencyUs = 0L; private var maxUs = 0L

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            levels = ByteArray(w * h) { 15 }
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }
        }

        override fun onDraw(cv: Canvas) {
            bitmap?.let { cv.drawBitmap(it, 0f, 0f, paint) }
            // Finger buttons along the top (Supernote has no Back key): 200 px wide, 80 px tall.
            for ((i, name) in buttons.withIndex()) {
                val x = width - (buttons.size - i) * 172f
                paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                cv.drawRect(x, 10f, x + 166f, 90f, paint)
                paint.style = Paint.Style.FILL
                cv.drawText(when (name) { "Mirror" -> "Mirror: $mirror"; "Flag" -> "Flag: $flag"; "Dab" -> if (flecks) "Dab: flecks" else "Dab: solid"; "Mode" -> "Mode: $mode"; "Refresh" -> "Refresh #$refreshIndex"; "Clean" -> "Clean: ${if (cleanOn) "on" else "off"}"; else -> name }, x + 16f, 62f, label)
            }
        }

        private fun fingerTap(x: Float, y: Float): Boolean {
            if (y > 90f) return false
            val i = buttons.indices.firstOrNull { x >= width - (buttons.size - it) * 172f && x < width - (buttons.size - it) * 172f + 200f } ?: return false
            when (buttons[i]) {
                "Refresh" -> {
                    val (hide, n) = refreshVariants[refreshIndex % refreshVariants.size]
                    val r = runCatching {
                        val sm = Class.forName("android.os.ServiceManager")
                        val binder = sm.getMethod("getService", String::class.java).invoke(null, "eink") as android.os.IBinder
                        val mgr = Class.forName("android.os.IEinkManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                        mgr.javaClass.getMethod("screenRefresh", java.lang.Boolean.TYPE, Integer.TYPE).invoke(mgr, hide, n); "ok"
                    }.getOrElse { "FAILED ${it.cause?.message ?: it.message}" }
                    Log.i(TAG, "screenRefresh(hide=$hide, n=$n) #$refreshIndex -> $r")
                    refreshIndex++
                }
                "Clean" -> { cleanOn = !cleanOn; Log.i(TAG, "clean=$cleanOn") }
                "Mode" -> { mode = if (mode == 7) 4 else 7; Log.i(TAG, "mode=$mode") }
                "Dab" -> { flecks = !flecks; Log.i(TAG, "flecks=$flecks") }
                "Flag" -> { flag = intArrayOf(0, 1, 5, 4)[(intArrayOf(0, 1, 5, 4).indexOf(flag) + 1) % 4]; Log.i(TAG, "flag=$flag") }
                "Mirror" -> { mirror = Mirror.values()[(mirror.ordinal + 1) % 3]; Log.i(TAG, "mirror=$mirror") }
                "Clear" -> { levels.fill(15); bitmap?.eraseColor(Color.WHITE); refreshMirror(Rect(0, 0, width, height)) }
                "Exit" -> finish()
            }
            invalidate()
            return true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) return e.actionMasked != MotionEvent.ACTION_DOWN || fingerTap(e.x, e.y)
            if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS && e.getToolType(0) != MotionEvent.TOOL_TYPE_ERASER) return false
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { strokeRect.setEmpty(); down = false }
            val t0 = System.nanoTime()
            val dirty = Rect()
            for (h in 0 until e.historySize) walk(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h), dirty)
            walk(e.x, e.y, e.pressure, dirty)
            samples += e.historySize + 1
            if (e.actionMasked == MotionEvent.ACTION_DOWN) removeCallbacks(cleanRunnable)
            if (!dirty.isEmpty) {
                dirty.intersect(0, 0, width, height)
                post(dirty)
                cleanRect.union(dirty)
                val us = (System.nanoTime() - t0) / 1000
                latencyUs += us; if (us > maxUs) maxUs = us; events++
                strokeRect.union(dirty)
                if (mirror == Mirror.LIVE) { refreshMirror(dirty); invalidate(dirty) }
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mirror == Mirror.UP && !strokeRect.isEmpty) { refreshMirror(strokeRect); invalidate(strokeRect) }
                if (cleanOn) postDelayed(cleanRunnable, 1500)
                Log.i(TAG, "stroke: $samples samples in $events events, event→panel avg ${latencyUs / maxOf(1, events)} µs max $maxUs µs (input→event ${SystemClock.uptimeMillis() - e.eventTime} ms at up)")
                samples = 0; events = 0; latencyUs = 0; maxUs = 0
            }
            return true
        }

        /** Advance the path to (x,y), stamping every [spacing] px along the way with interpolated pressure. */
        private fun walk(x: Float, y: Float, p: Float, dirty: Rect) {
            if (!down) { down = true; lastX = x; lastY = y; lastP = p; carry = 0f; stamp(x, y, p, dirty); return }
            val dx = x - lastX; val dy = y - lastY
            val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            var t = spacing - carry
            while (t <= len) {
                val f = t / len
                stamp(lastX + dx * f, lastY + dy * f, lastP + (p - lastP) * f, dirty)
                t += spacing
            }
            carry = len - (t - spacing)
            lastX = x; lastY = y; lastP = p
        }

        /** A round dab of radius RADIUS at (x,y); pressure 0→light, 1→black; darken-only. */
        private fun stamp(x: Float, y: Float, pressure: Float, dirty: Rect) {
            val level = if (flecks) 0 else (15 - (pressure * 18f).toInt()).coerceIn(0, 15)
            val density = (pressure * 1.2f).coerceIn(0.04f, 1f)   // flecks: share of the disc painted black
            val cx = x.toInt(); val cy = y.toInt()
            for (dy in -RADIUS..RADIUS) for (dx in -RADIUS..RADIUS) {
                if (dx * dx + dy * dy > RADIUS * RADIUS) continue
                if (flecks && rng.nextFloat() > density) continue
                val px = cx + dx; val py = cy + dy
                if (px < 0 || py < 0 || px >= width || py >= height) continue
                val i = py * width + px
                if (level < levels[i]) levels[i] = level.toByte()
            }
            dirty.union(Rect(cx - RADIUS, cy - RADIUS, cx + RADIUS + 1, cy + RADIUS + 1))
        }

        /**
         * Screen rect → frame 0 + one DISPAREA. Two panel orientations seen: the Nomad's panel is
         * landscape under a portrait screen (panelX = screenY, panelY = panelH−1 − screenX); the
         * Manta's panel is the screen (identity). Chosen by geometry, measured on both.
         */
        private val rotated get() = panelW != width
        private fun post(r: Rect) {
            val m = map ?: return
            for (sy in r.top until r.bottom) for (sx in r.left until r.right) {
                val i = if (rotated) (panelH - 1 - sx) * panelW + sy else sy * panelW + sx
                m.put(i, (levels[sy * width + sx] * scale).toByte())
            }
            val arg = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN)
            if (rotated) { arg.putInt(0, r.top); arg.putInt(4, panelH - r.right); arg.putInt(8, r.bottom); arg.putInt(12, panelH - r.left) }
            else { arg.putInt(0, r.left); arg.putInt(4, r.top); arg.putInt(8, r.right); arg.putInt(12, r.bottom) }
            arg.putInt(16, 0); arg.put(20, mode.toByte()); arg.put(21, flag.toByte())
            val ret = Native.ioctl(fd, REQ_DISPAREA, arg)
            if (ret < 0) Log.w(TAG, "DISPAREA failed ${Native.strerror(-ret)}")
        }

        /** Re-display [cleanRect] in mode 4 at the 0..60 scale: same pixels, every one driven. */
        private fun cleanPass() {
            val m = map ?: return
            if (cleanRect.isEmpty) return
            val r = Rect(cleanRect); cleanRect.setEmpty()
            for (sy in r.top until r.bottom) for (sx in r.left until r.right) {
                val i = if (rotated) (panelH - 1 - sx) * panelW + sy else sy * panelW + sx
                m.put(i, (levels[sy * width + sx] * 4).toByte())
            }
            val arg = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN)
            if (rotated) { arg.putInt(0, r.top); arg.putInt(4, panelH - r.right); arg.putInt(8, r.bottom); arg.putInt(12, panelH - r.left) }
            else { arg.putInt(0, r.left); arg.putInt(4, r.top); arg.putInt(8, r.right); arg.putInt(12, r.bottom) }
            arg.putInt(16, 0); arg.put(20, 4.toByte()); arg.put(21, flag.toByte())
            val t0 = System.nanoTime()
            val ret = Native.ioctl(fd, REQ_DISPAREA, arg)
            Log.i(TAG, "clean pass $r mode 4 -> $ret in ${(System.nanoTime() - t0) / 1_000_000} ms")
            // Put the mode-7 scale back under the rect for whatever the next stroke touches.
            for (sy in r.top until r.bottom) for (sx in r.left until r.right) {
                val i = if (rotated) (panelH - 1 - sx) * panelW + sy else sy * panelW + sx
                m.put(i, (levels[sy * width + sx] * scale).toByte())
            }
        }

        private fun refreshMirror(r: Rect) {
            val b = bitmap ?: return
            val w = r.width(); val h = r.height()
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val g = MIRROR_GREY[levels[(r.top + y) * width + r.left + x].toInt()]
                px[y * w + x] = Color.rgb(g, g, g)
            }
            b.setPixels(px, 0, w, r.left, r.top, w, h)
        }
    }
}
