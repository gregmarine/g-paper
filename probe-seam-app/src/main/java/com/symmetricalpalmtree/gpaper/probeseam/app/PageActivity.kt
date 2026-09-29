package com.symmetricalpalmtree.gpaper.probeseam.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.gpaper.probeseam.ISeam
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Real notebook pages on the real engine, every page read through the hub. Prev / Next or a
 * one-finger swipe flips; the line under the buttons says what the flip cost (logcat SeamFlip).
 * Nothing is cached or prefetched — each flip is the worst case.
 */
class PageActivity : Activity() {
    private lateinit var paper: PaperView
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()
    private var seam: ISeam? = null
    private var books: Array<String> = emptyArray()
    private var book = 0
    private var pages: List<String> = emptyList()
    private var page = 0
    private var downX = 0f
    private var downY = 0f

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            seam = ISeam.Stub.asInterface(service)
            io.execute { books = seam!!.notebooks(); openBook(0) }
        }
        override fun onServiceDisconnected(name: ComponentName) { seam = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paper = GPaper.create(this)
        paper.directInk = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(label: String, act: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 18f
            setOnClickListener { act() }
            bar.addView(this, LinearLayout.LayoutParams(0, 140, 1f))
        }
        button("Prev") { flip(-1) }
        button("Next") { flip(+1) }
        button("Notebook") { io.execute { if (books.isNotEmpty()) openBook((book + 1) % books.size) } }
        status = TextView(this).apply { textSize = 15f; setTextColor(Color.BLACK); setPadding(16, 8, 16, 8) }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(paper.asView(), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        bindService(Intent().setClassName("com.symmetricalpalmtree.gpaper.probeseam.hub", "com.symmetricalpalmtree.gpaper.probeseam.hub.HubService"), conn, Context.BIND_AUTO_CREATE)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; if (ev.y < paper.asView().top) paper.releaseRender() }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    if (downY > paper.asView().top && abs(dx) > 150 && abs(dx) > 2 * abs(ev.y - downY)) flip(if (dx < 0) +1 else -1)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() { super.onResume(); paper.resumeDrawing() }

    override fun onDestroy() {
        runCatching { unbindService(conn) }
        paper.release()
        io.shutdown()
        super.onDestroy()
    }

    private fun openBook(index: Int) {
        val s = seam ?: return
        book = index
        pages = s.pages(books[book]).toList()
        page = 0
        show()
    }

    private fun flip(by: Int) {
        if (pages.isEmpty()) return
        val next = (page + by).coerceIn(0, pages.size - 1)
        if (next == page) return
        page = next
        io.execute { show() }
    }

    /** On the io thread: read through the seam, decode, then hand the page to the engine. */
    private fun show() {
        val s = seam ?: return
        val (id, w, h) = pages[page].split('|')
        val t0 = System.nanoTime()
        val block = s.loadNotebookPage(books[book], id)
        val t1 = System.nanoTime()
        val strokes = decode(block)
        val t2 = System.nanoTime()
        val inHub = s.baselinePageNanos(books[book], id)
        runOnUiThread {
            val t3 = System.nanoTime()
            paper.setPageSize(w.toInt(), h.toInt())
            paper.loadStrokes(strokes)
            val t4 = System.nanoTime()
            val line = "%s · page %d of %d · %d strokes · seam read %.0f ms (in hub %.0f) · decode %.0f · engine %.0f · total %.0f ms".format(
                books[book].removeSuffix(".soil"), page + 1, pages.size, strokes.size,
                (t1 - t0) / 1e6, inHub / 1e6, (t2 - t1) / 1e6, (t4 - t3) / 1e6, ((t2 - t0) + (t4 - t3)) / 1e6)
            Log.i(TAG, line)
            status.text = line
        }
    }

    private fun decode(m: SharedMemory): List<Stroke> {
        val b: ByteBuffer = m.mapReadOnly()
        val out = ArrayList<Stroke>(b.getInt())
        repeat(out.let { b.getInt(0) }) {
            val id = ByteArray(36).also { b.get(it) }.toString(Charsets.US_ASCII)
            val color = b.getInt(); val width = b.getFloat()
            val style = ByteArray(b.get().toInt()).also { b.get(it) }.toString(Charsets.US_ASCII)
            val blob = ByteArray(b.getInt()).also { b.get(it) }
            val points = points(blob)
            if (points.isNotEmpty()) out += Stroke(id, points, color, width, runCatching { StrokeStyle.valueOf(style) }.getOrDefault(StrokeStyle.PEN))
        }
        SharedMemory.unmap(b); m.close()
        return out
    }

    /** The `.soil` stroke blob, format B: version byte, then zlib{flags, x y [pressure] [tilt]…}. */
    private fun points(blob: ByteArray): List<StrokePoint> {
        if (blob.isEmpty() || blob[0] != 1.toByte()) return emptyList()
        val inf = Inflater().apply { setInput(blob, 1, blob.size - 1) }
        val out = java.io.ByteArrayOutputStream(blob.size * 3)
        val buf = ByteArray(4096)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
        inf.end()
        val p = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        if (!p.hasRemaining()) return emptyList()
        val flags = p.get().toInt()
        val pressure = flags and 1 != 0; val tilt = flags and 2 != 0
        val n = p.remaining() / (8 + (if (pressure) 4 else 0) + (if (tilt) 4 else 0))
        return List(n) { StrokePoint(p.float, p.float, if (pressure) p.float else 1f, if (tilt) p.float else 0f) }
    }

    private companion object { const val TAG = "SeamFlip" }
}
