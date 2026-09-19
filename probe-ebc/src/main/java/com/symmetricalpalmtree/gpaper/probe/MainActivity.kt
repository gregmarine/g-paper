package com.symmetricalpalmtree.gpaper.probe

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One screen, one log, a row of buttons. Each button is one syscall against /dev/ebc,
 * and the log shows exactly what came back. Nothing here is automated — the driver is
 * unknown territory and every call is a hand press.
 *
 * Request numbers were read out of /system/lib64/libeinkutils.so (initEbc /
 * updateSFSettings) on the Nomad, firmware Chauvet.E103…2389:
 *   0x48545201  'R',1  — initEbc's first call after open; its result sizes the mmap
 *   0x48544206  'B',6  — HTEINK_IOC_GETCFG (fills a 132-byte hteink_config)
 *   0x48545701  'W',1  — HTEINK_IOC_DISPAREA (-F/-H/-R variants: an area to display)
 *   0x48545801  'X',1  — HTEINK_IOC_MISCCTL (CLERA_PW_RECT, HTEINK_MISC_SYNCWIN)
 * DISPAREA's argument (from postEinkHostBmpRectFast / postEinkPWRectFast), 24 bytes:
 *   int32 left, top, right, bottom   — panel coordinates, after the library's own rotation
 *   int32 bufOffset                  — byte offset of the source frame inside the mmapped region
 *   u8    mode                       — host path 4 / 7 / 8 / 9 (app modes 3 / 4–14 / 15 / 16); pen-write 9
 *   u8    flag                       — host path 0; pen-write 1 or 5
 *   pad to 24
 * The region is mmap(5 × frameBytes) at offset 0; frame 0 is the host bitmap frame, frame 1
 * the pen-write frame (initEbc keeps base + frameBytes as its "pw fsb"). Frames are 8 bpp.
 * MISCCTL is still unwired.
 */
class MainActivity : Activity() {
    private companion object {
        const val TAG = "EbcProbe"
        const val DEV_EBC = "/dev/ebc"
        const val O_CLOEXEC = 0x80000
        const val REQ_R1 = 0x48545201L
        const val REQ_GETCFG = 0x48544206L
        const val REQ_DISPAREA = 0x48545701L
        const val BUF_BYTES = 4096
        const val FRAMES = 5
    }

    private var frameBytes = 0L
    private var panelW = 0
    private var panelH = 0
    private var map: ByteBuffer? = null

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private var fd = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = buttons.addView(
            Button(this).apply { text = label; setOnClickListener { runCatching(onClick).onFailure { say("!! ${it}") } } }
        )
        button("Who am I") { whoAmI() }
        button("Open") { open() }
        button("R1") { ioctlDump("R1", REQ_R1) }
        button("GETCFG") { ioctlDump("GETCFG", REQ_GETCFG) }
        button("Map") { mapFrames() }
        button("m7") { bands(mode = 7, frame = 0, flag = 0) }
        button("m4") { bands(mode = 4, frame = 0, flag = 0) }
        button("m8") { bands(mode = 8, frame = 0, flag = 0) }
        button("m9") { bands(mode = 9, frame = 0, flag = 0) }
        button("PW") { bands(mode = 9, frame = 1, flag = 1) }
        button("Unmap") { unmap() }
        button("Close") { close() }
        button("Clear") { log.text = "" }
        root.addView(buttons)
        log = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setPadding(16, 16, 16, 16) }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        whoAmI()
        // adb shell am start -n com.symmetricalpalmtree.gpaper.probe/.MainActivity --ez auto true
        // runs the read-only sequence hands-free; results land in logcat under EbcProbe.
        // --ei mode 7 --ei frame 0 --ei flag 0 --ei v0 0 --ei v1 255 then paints the band rect too.
        if (intent.getBooleanExtra("auto", false)) {
            say("-- auto: open, R1, GETCFG, map")
            open(); ioctlDump("R1", REQ_R1); ioctlDump("GETCFG", REQ_GETCFG); mapFrames()
            val mode = intent.getIntExtra("mode", -1)
            if (mode >= 0) bands(mode, intent.getIntExtra("frame", 0), intent.getIntExtra("flag", 0),
                intent.getIntExtra("v0", 0), intent.getIntExtra("v1", 15))
            say("-- auto done (still open + mapped)")
        }
    }

    override fun onDestroy() {
        map?.let { Native.munmap(it, frameBytes * FRAMES) }
        if (fd >= 0) Native.close(fd)
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        log.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun whoAmI() {
        val ctx = runCatching { File("/proc/self/attr/current").readText().trim('\u0000', '\n') }.getOrElse { "unreadable: $it" }
        say("uid=${Process.myUid()} pid=${Process.myPid()} selinux=$ctx")
        val ebc = File(DEV_EBC)
        say("$DEV_EBC exists=${ebc.exists()} canRead=${ebc.canRead()} canWrite=${ebc.canWrite()}")
    }

    private fun open() {
        if (fd >= 0) { say("already open fd=$fd"); return }
        val r = Native.open(DEV_EBC, Native.O_RDWR or O_CLOEXEC)
        if (r >= 0) { fd = r; say("open($DEV_EBC, O_RDWR|O_CLOEXEC) = fd $fd") }
        else say("open($DEV_EBC) FAILED errno=${-r} ${Native.strerror(-r)}")
    }

    private fun close() {
        if (fd < 0) { say("not open"); return }
        val r = Native.close(fd)
        say(if (r == 0) "close(fd $fd) ok" else "close FAILED errno=${-r} ${Native.strerror(-r)}")
        fd = -1
    }

    /** R1 for the frame geometry, then mmap the five frames initEbc maps. */
    private fun mapFrames() {
        if (fd < 0) { say("open first"); return }
        if (map != null) { say("already mapped"); return }
        val buf = ByteBuffer.allocateDirect(BUF_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val r = Native.ioctl(fd, REQ_R1, buf)
        if (r < 0) { say("R1 FAILED errno=${-r} ${Native.strerror(-r)}"); return }
        panelW = buf.getInt(4) and 0xFFFF
        panelH = buf.getInt(4) ushr 16
        frameBytes = buf.getInt(12).toLong()
        val len = frameBytes * FRAMES
        val m = Native.mmap(fd, len, 0)
        if (m == null) { val e = Native.lastErrno(); say("mmap($len) FAILED errno=$e ${Native.strerror(e)}"); return }
        map = m.order(ByteOrder.LITTLE_ENDIAN)
        say("mmap($len) ok — panel ${panelW}x${panelH}, frame $frameBytes B, $FRAMES frames")
        // Frame 0 as it is right now — a sample says whether the driver keeps the screen here.
        val sample = (0 until 8).map { String.format("%02x", m.get((it * frameBytes / 8).toInt()).toInt() and 0xFF) }
        say("  frame0 samples: ${sample.joinToString(" ")}")
    }

    private fun unmap() {
        val m = map ?: run { say("not mapped"); return }
        val r = Native.munmap(m, frameBytes * FRAMES)
        say(if (r == 0) "munmap ok" else "munmap FAILED errno=${-r}")
        map = null
    }

    /**
     * Sixteen vertical bands of grey from [v0] to [v1] written into [frame], then one
     * DISPAREA for that rect with [mode]/[flag]. Panel coordinates, rotation ignored.
     */
    private fun bands(mode: Int, frame: Int, flag: Int, v0: Int = 0, v1: Int = 15) {
        if (map == null) { say("map first"); return }
        // Our own window must be still: a TextView append repaints it, and the compositor's
        // repaint of the screen would overwrite the rect before an eye could see it. So paint
        // after the UI has settled and keep the screen log silent for three seconds after.
        say("painting in 1 s, screen quiet for 3 s after")
        log.postDelayed({
            val line = paintBands(mode, frame, flag, v0, v1)
            Log.i(TAG, line)
            log.postDelayed({ say(line) }, 3000)
        }, 1000)
    }

    private fun paintBands(mode: Int, frame: Int, flag: Int, v0: Int, v1: Int): String {
        val m = map!!
        val l = panelW / 8; val t = panelH / 4; val r = panelW * 7 / 8; val b = panelH * 3 / 4
        val base = frame * frameBytes
        val bandW = (r - l) / 16
        for (y in t until b) {
            val row = base + y.toLong() * panelW
            for (x in l until r) {
                val k = ((x - l) / bandW).coerceAtMost(15)
                val v = v0 + (v1 - v0) * k / 15
                m.put((row + x).toInt(), v.toByte())
            }
        }
        val arg = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN)
        arg.putInt(0, l); arg.putInt(4, t); arg.putInt(8, r); arg.putInt(12, b)
        arg.putInt(16, base.toInt()); arg.put(20, mode.toByte()); arg.put(21, flag.toByte())
        val ret = Native.ioctl(fd, REQ_DISPAREA, arg)
        return "DISPAREA [$l,$t-$r,$b] frame=$frame mode=$mode flag=$flag v=$v0..$v1 -> " +
            if (ret < 0) "FAILED errno=${-ret} ${Native.strerror(-ret)}" else "$ret"
    }

    /** One ioctl with a zeroed buffer; prints ret/errno and the bytes the kernel wrote. */
    private fun ioctlDump(name: String, req: Long) {
        if (fd < 0) { say("open first"); return }
        val buf = ByteBuffer.allocateDirect(BUF_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val r = Native.ioctl(fd, req, buf)
        if (r < 0) { say("ioctl $name 0x${req.toString(16)} FAILED errno=${-r} ${Native.strerror(-r)}"); return }
        say("ioctl $name 0x${req.toString(16)} = $r")
        var last = 0
        for (i in 0 until BUF_BYTES) if (buf.get(i).toInt() != 0) last = i
        say("  bytes written: 0..$last (${last + 1})")
        val words = (last / 4 + 1).coerceAtMost(64)
        val sb = StringBuilder()
        for (w in 0 until words) {
            val v = buf.getInt(w * 4)
            sb.append(String.format("  [%3d] +0x%03x  %d  0x%08x\n", w, w * 4, v, v))
        }
        say(sb.toString().trimEnd())
    }
}
