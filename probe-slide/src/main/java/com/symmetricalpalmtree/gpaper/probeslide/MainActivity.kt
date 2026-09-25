package com.symmetricalpalmtree.gpaper.probeslide

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Door 1: every KeyEvent the activity receives is logged, the side-bar codes named.
 * A bar slide is a burst of key presses; the burst is summarised once it goes quiet.
 *
 * Door 2: the launcher's side menu / status bar locked and unlocked two ways —
 *  - the broadcast Ratta's own Notes app sends (`…BroadcastReceiver.slidebarstatusbar`,
 *    extras `lockSlidebar` / `lockStatusbar` / `packageName`); the launcher unlocks by
 *    itself when the named process is gone;
 *  - the exported `GestureService` binder: transactions 4 (lockSlidebar) and 5
 *    (lockStatusbar), one int each; 3 (onFirstKeyCode) with 82 opens the side menu.
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    // Burst summary per bar: code -> count, flushed after a quiet period.
    private val burst = HashMap<Int, Int>()
    private var burstStart = 0L
    private val flush = Runnable { flushBurst() }

    private var gesture: IBinder? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            gesture = service
            say("binder: connected ${service.interfaceDescriptor}")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            gesture = null
            say("binder: disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onTap: () -> Unit) = Button(this).apply {
            text = label
            setTextColor(Color.BLACK)
            setOnClickListener { onTap() }
        }
        val r1 = row()
        r1.addView(button("Lock (bcast)") { lockBroadcast(true) })
        r1.addView(button("Unlock (bcast)") { lockBroadcast(false) })
        r1.addView(button("Bind") { bind() })
        root.addView(r1)
        val r2 = row()
        r2.addView(button("Lock (binder)") { lockBinder(true) })
        r2.addView(button("Unlock (binder)") { lockBinder(false) })
        r2.addView(button("Menu (binder 82)") { firstKeyCode(82) })
        r2.addView(button("Clear") { log.text = "" })
        root.addView(r2)
        status = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            text = "Swipe a side bar. Keys reaching this activity are listed below."
            setPadding(16, 8, 16, 8)
        }
        root.addView(status)
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(16, 8, 16, 8)
        }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        say("pid ${android.os.Process.myPid()} — ${packageName}")
    }

    override fun onDestroy() {
        // Never leave the device locked: undo both doors on the way out.
        runCatching { lockBinder(false) }
        runCatching { lockBroadcast(false) }
        runCatching { if (gesture != null) unbindService(conn) }
        super.onDestroy()
    }

    // ---- door 1: the key stream -------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val name = when (code) {
            300, 301 -> "BAR-A"
            309, 310 -> "BAR-B"
            290 -> "DRAG"
            291 -> "REFRESH"
            292 -> "SLIDE"
            82 -> "MENU"
            else -> KeyEvent.keyCodeToString(code)
        }
        val act = when (event.action) { KeyEvent.ACTION_DOWN -> "dn"; KeyEvent.ACTION_UP -> "up"; else -> "?" }
        val dev = event.device?.name ?: "dev${event.deviceId}"
        if (code in 300..301 || code in 309..310) {
            if (burst.isEmpty()) burstStart = System.currentTimeMillis()
            burst[code] = (burst[code] ?: 0) + 1
            main.removeCallbacks(flush)
            main.postDelayed(flush, 400)
        } else {
            say("key $code $name $act rep=${event.repeatCount} scan=${event.scanCode} [$dev]")
        }
        return true // consumed: nothing else in this app wants them
    }

    private fun flushBurst() {
        if (burst.isEmpty()) return
        val bar = if (burst.keys.any { it >= 309 }) "BAR-B (309/310)" else "BAR-A (300/301)"
        val parts = burst.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}×${it.value}" }
        val ms = System.currentTimeMillis() - burstStart
        say("slide on $bar: $parts events over ~${ms}ms")
        burst.clear()
    }

    // ---- door 2a: the broadcast the Notes app uses ------------------------------

    private fun lockBroadcast(lock: Boolean) {
        val i = Intent("com.ratta.supernote.launcher.BroadcastReceiver.slidebarstatusbar")
            .putExtra("lockSlidebar", lock)
            .putExtra("lockStatusbar", lock)
            .putExtra("packageName", packageName)
        try {
            sendBroadcast(i)
            say("broadcast: lockSlidebar=$lock lockStatusbar=$lock packageName=$packageName — now swipe the bar")
        } catch (e: SecurityException) {
            say("broadcast: REFUSED — ${e.message}")
        }
    }

    // ---- door 2b: the exported GestureService binder ----------------------------

    private fun bind() {
        val i = Intent().setComponent(ComponentName(LAUNCHER, "$LAUNCHER.service.GestureService"))
        val ok = try { bindService(i, conn, Context.BIND_AUTO_CREATE) } catch (e: SecurityException) {
            say("binder: bindService threw ${e.message}"); false
        }
        say("binder: bindService → $ok")
    }

    private fun transact(code: Int, label: String, write: (Parcel) -> Unit) {
        val b = gesture ?: run { say("binder: not bound — tap Bind first"); return }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            val ok = b.transact(code, data, reply, 0)
            reply.readException()
            say("binder: $label → $ok")
        } catch (e: Exception) {
            say("binder: $label threw ${e.javaClass.simpleName}: ${e.message}")
        } finally { data.recycle(); reply.recycle() }
    }

    private fun lockBinder(lock: Boolean) {
        transact(4, "lockSlidebar($lock)") { it.writeInt(if (lock) 1 else 0) }
        transact(5, "lockStatusbar($lock)") { it.writeInt(if (lock) 1 else 0) }
    }

    private fun firstKeyCode(code: Int) = transact(3, "onFirstKeyCode($code)") { it.writeInt(code) }

    private fun say(line: String) {
        Log.d(TAG, line)
        log.append("${stamp.format(Date())}  $line\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val TAG = "SlideProbe"
        private const val LAUNCHER = "com.ratta.supernote.launcher"
        private const val DESCRIPTOR = "com.ratta.supernote.launcher.IGestureInterface"
    }
}
