package com.symmetricalpalmtree.gpaper.launcherdemo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen. While it is in front the firmware side menu is held shut through the
 * launcher's binder (re-locked on every focus gain, because the launcher clears the lock
 * on each foreground change), and the right bar's key stream is classified on key-up:
 *
 *  - press shorter than [TAP_MS]: a tap, ignored;
 *  - longer, with the firmware's refresh broadcast seen during it: swipe UP, ignored
 *    (the panel refresh is the firmware's, it happens regardless of the lock);
 *  - longer, no refresh: swipe DOWN — open this app's own side menu.
 */
class HomeActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var menu: View
    private lateinit var scrim: View
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    private var gesture: IBinder? = null
    private var bindRequested = false
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            gesture = service; say("binder connected"); lock(true)
        }
        override fun onServiceDisconnected(name: ComponentName) { gesture = null; say("binder gone") }
    }

    // Right-bar press bookkeeping.
    private var rightDownAt = 0L
    private var refreshSeen = false
    private var lockedOnce = false

    private val firmware = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (BarService.running) return
            when (i.action) {
                REFRESH -> { refreshSeen = true; say("firmware: refresh (swipe up)") }
                MENU_STATE -> {
                    val show = i.getBooleanExtra("show", false)
                    say("firmware: side menu show=$show")
                    // The lock was lost (an app switch clears it) and the firmware menu is
                    // on its way up: lock again — that also removes its view — and take over.
                    if (show) { lock(true); main.postDelayed({ lock(true) }, 400); openMenu() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        page.addView(TextView(this).apply {
            text = "Launcher demo"
            setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f); typeface = Typeface.DEFAULT_BOLD
        })
        page.addView(TextView(this).apply {
            text = "Swipe DOWN on the right bar for this app's menu.\nSwipe up is the firmware refresh and is ignored.\n" +
                "Restore Ratta's home with:\n  pm set-home-activity com.ratta.supernote.background/.MainActivity"
            setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setPadding(0, 24, 0, 24)
        })
        val row = LinearLayout(this)
        row.addView(button("Open menu") { openMenu() })
        row.addView(button("Open Notesprout Dev") { launch(NOTESPROUT_DEV) })
        row.addView(button("Clear log") { log.text = "" })
        page.addView(row)
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE; setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        scroll = ScrollView(this).apply { addView(log) }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(page)

        // Our side menu: a scrim over the page and a panel on the right edge.
        scrim = View(this).apply { setBackgroundColor(Color.TRANSPARENT); visibility = View.GONE; setOnClickListener { closeMenu() } }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(3, Color.BLACK) }
            visibility = View.GONE
            setPadding(24, 24, 24, 24)
            addView(TextView(this@HomeActivity).apply {
                text = "Menu"; setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD
                setPadding(16, 8, 16, 24)
            })
            addView(menuRow("Notesprout Dev") { launch(NOTESPROUT_DEV) })
            addView(menuRow("Slide probe") { launch("com.symmetricalpalmtree.gpaper.probeslide") })
            addView(menuRow("Ratta home") { launchComponent("com.ratta.supernote.background", "com.ratta.supernote.background.MainActivity") })
            addView(menuRow("Close") { closeMenu() })
        }
        root.addView(menu, FrameLayout.LayoutParams(520, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        setContentView(root)
        say("home up — pid ${android.os.Process.myPid()} — bar service ${if (BarService.running) "RUNNING (owns the bars)" else "off"}")
    }

    private fun button(label: String, onTap: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.BLACK); setOnClickListener { onTap() }
    }

    private fun menuRow(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(16, 28, 16, 28)
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(2, Color.BLACK) }
        setOnClickListener { closeMenu(); onTap() }
    }

    // ---- lifecycle: the lock lives only until the next app switch ---------------

    override fun onStart() {
        super.onStart()
        registerReceiver(firmware, IntentFilter().apply { addAction(REFRESH); addAction(MENU_STATE) })
        if (!bindRequested) {
            bindRequested = true
            val i = Intent().setComponent(ComponentName(LAUNCHER, "$LAUNCHER.service.GestureService"))
            say("bindService → " + runCatching { bindService(i, conn, Context.BIND_AUTO_CREATE) }.getOrElse { "threw $it" })
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !BarService.running) {
            // The launcher's own app-changed handler re-enables the bars around now; land after it.
            main.postDelayed({ lock(true) }, 300)
            main.postDelayed({ lock(true) }, 1200)
        }
    }

    override fun onPause() {
        if (!BarService.running) lock(false) // the next app gets the firmware menu back at once, not on the next switch
        super.onPause()
    }

    override fun onStop() { unregisterReceiver(firmware); super.onStop() }

    override fun onDestroy() {
        runCatching { lock(false) }
        runCatching { if (gesture != null) unbindService(conn) }
        super.onDestroy()
    }

    // ---- the right bar --------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in 290..292 && code !in 300..301 && code !in 309..310 && code != KeyEvent.KEYCODE_MENU) {
            return super.dispatchKeyEvent(event)
        }
        if (BarService.running) return true // the service owns the bars
        if (!lockedOnce) lock(true) // first bar touch after a switch: make sure the firmware menu is shut
        if (code == RIGHT_FIRST) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) { rightDownAt = event.eventTime; refreshSeen = false }
                KeyEvent.ACTION_UP -> {
                    val held = event.eventTime - rightDownAt
                    // The refresh broadcast lands ~200 ms before the finger lifts; give it a moment more.
                    main.postDelayed({ classify(held) }, 150)
                }
            }
        }
        return true
    }

    private fun classify(heldMs: Long) {
        when {
            heldMs < TAP_MS -> say("right bar: tap (${heldMs}ms) — ignored")
            refreshSeen -> say("right bar: swipe UP (${heldMs}ms, refresh seen) — ignored")
            else -> { say("right bar: swipe DOWN (${heldMs}ms) — menu"); openMenu() }
        }
    }

    // ---- our menu ---------------------------------------------------------------

    private fun openMenu() { scrim.visibility = View.VISIBLE; menu.visibility = View.VISIBLE }
    private fun closeMenu() { menu.visibility = View.GONE; scrim.visibility = View.GONE }

    override fun onBackPressed() { if (menu.visibility == View.VISIBLE) closeMenu() /* a home never finishes */ }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { say("launch: $pkg not installed"); return }
        say("launch: $pkg"); startActivity(i)
    }

    private fun launchComponent(pkg: String, cls: String) {
        say("launch: $pkg/$cls")
        runCatching { startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { say("launch failed: $it") }
    }

    // ---- the firmware launcher's binder ------------------------------------------

    private fun transact(code: Int, arg: Int): Boolean {
        val b = gesture ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeInt(arg)
            b.transact(code, data, reply, 0); reply.readException(); true
        } catch (e: Exception) { say("binder $code threw ${e.javaClass.simpleName}"); false }
        finally { data.recycle(); reply.recycle() }
    }

    private fun lock(on: Boolean) {
        if (gesture == null) return
        val a = transact(4, if (on) 1 else 0) // lockSlidebar — also removes the firmware menu view
        val b = transact(5, if (on) 1 else 0) // lockStatusbar
        lockedOnce = on
        Log.d(TAG, "lock($on) → $a/$b")
    }

    private fun say(line: String) {
        Log.d(TAG, line)
        log.append("${stamp.format(Date())}  $line\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val TAG = "LauncherDemo"
        private const val LAUNCHER = "com.ratta.supernote.launcher"
        private const val DESCRIPTOR = "com.ratta.supernote.launcher.IGestureInterface"
        private const val REFRESH = "com.ratta.supernote.launcher.flashscreen"
        private const val MENU_STATE = "com.ratta.supernote.launcher.slidebarstatusbarstate"
        private const val NOTESPROUT_DEV = "com.symmetricalpalmtree.notesproutsn.dev"
        private const val RIGHT_FIRST = 310
        private const val TAP_MS = 250L
    }
}
