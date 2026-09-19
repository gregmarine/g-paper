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
 * Only the two reads are wired up. DISPAREA and MISCCTL wait until their structs are known.
 */
class MainActivity : Activity() {
    private companion object {
        const val TAG = "EbcProbe"
        const val DEV_EBC = "/dev/ebc"
        const val O_CLOEXEC = 0x80000
        const val REQ_R1 = 0x48545201L
        const val REQ_GETCFG = 0x48544206L
        const val BUF_BYTES = 4096
    }

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
        if (intent.getBooleanExtra("auto", false)) {
            say("-- auto: open, R1, GETCFG, close")
            open(); ioctlDump("R1", REQ_R1); ioctlDump("GETCFG", REQ_GETCFG); close()
            say("-- auto done")
        }
    }

    override fun onDestroy() {
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
