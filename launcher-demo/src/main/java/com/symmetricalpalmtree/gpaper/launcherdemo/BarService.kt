package com.symmetricalpalmtree.gpaper.launcherdemo

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The system-wide bar owner. An accessibility service with key filtering sees every key
 * event before the foreground app does, in every app. The bar keys are observed, not
 * consumed: consuming them would also stop the firmware launcher from seeing the swipe,
 * and its refresh broadcast on swipe up is the only direction signal there is.
 *
 * The firmware side menu is held shut through the launcher's binder, re-locked after every
 * window change (the launcher clears the lock on each foreground switch). A right-bar
 * swipe down opens this service's own menu as an accessibility overlay on top of whatever
 * is in front.
 */
class BarService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var gesture: IBinder? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            gesture = service; log("binder connected")
            transact(5, 0) // the status bar is never ours — release it in case an earlier run left it shut
            lock(true)
        }
        override fun onServiceDisconnected(name: ComponentName) { gesture = null; log("binder gone") }
    }

    private var rightDownAt = 0L
    private var refreshSeen = false
    private var lastPackage: CharSequence? = null
    private var userLaunched = false
    private var overlay: View? = null

    private val firmware = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                REFRESH -> { refreshSeen = true; log("firmware: refresh (swipe up)") }
                // The firmware sends this for BOTH overlays: the side menu (our bar) and the
                // pull-down status bar (a top-edge drag, KEYCODE_DRAG). Only a recent right-bar
                // touch makes it a side-menu leak; the status bar is left alone.
                MENU_STATE -> if (i.getBooleanExtra("show", false)) {
                    if (android.os.SystemClock.uptimeMillis() - rightDownAt < 1500) {
                        log("firmware: side menu slipped through — locking and taking over")
                        lock(true); main.postDelayed({ lock(true) }, 400); showMenu()
                    } else log("firmware: status bar shown — left alone")
                }
            }
        }
    }

    override fun onServiceConnected() {
        running = true
        log("service connected — key filtering ${if (serviceInfo.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0) "ON" else "OFF"}")
        registerReceiver(firmware, IntentFilter().apply { addAction(REFRESH); addAction(MENU_STATE) })
        val i = Intent().setComponent(ComponentName(LAUNCHER, "$LAUNCHER.service.GestureService"))
        log("bindService → " + runCatching { bindService(i, conn, Context.BIND_AUTO_CREATE) }.getOrElse { "threw $it" })
    }

    override fun onDestroy() {
        running = false
        hideMenu()
        runCatching { lock(false) }
        runCatching { unregisterReceiver(firmware) }
        runCatching { if (gesture != null) unbindService(conn) }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // Every foreground change clears the firmware lock; land two re-locks after its handler.
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName ?: return
        if (pkg == LAUNCHER || pkg == packageName && overlay != null) return
        if (pkg != lastPackage) {
            lastPackage = pkg
            log("front: $pkg")
            // Ratta's gesture service runs its own boot routine ~15 s after boot and pushes its
            // "last package" (Notes by default) over whatever HOME is. Within the boot window,
            // a Notes arrival the user did not ask for is that push: take the home back.
            if (pkg == RATTA_NOTES && !userLaunched && android.os.SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
                log("boot push of Notes — returning to HOME")
                main.postDelayed({
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }, 300)
            }
            main.postDelayed({ lock(true) }, 300)
            main.postDelayed({ lock(true) }, 1200)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val bar = code in 300..301 || code in 309..310 || code in 290..292
        if (!bar) return false
        val act = if (event.action == KeyEvent.ACTION_DOWN) "dn" else "up"
        if (event.repeatCount == 0) log("key $code $act")
        if (code == RIGHT_FIRST) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) { rightDownAt = event.eventTime; refreshSeen = false; lock(true) }
                KeyEvent.ACTION_UP -> { val held = event.eventTime - rightDownAt; main.postDelayed({ classify(held) }, 150) }
            }
        }
        return CONSUME
    }

    private fun classify(heldMs: Long) {
        when {
            heldMs < TAP_MS -> log("right bar: tap (${heldMs}ms) — ignored")
            refreshSeen -> log("right bar: swipe UP (${heldMs}ms) — ignored")
            else -> { log("right bar: swipe DOWN (${heldMs}ms) — menu"); showMenu() }
        }
    }

    // ---- the overlay menu ---------------------------------------------------------

    private fun showMenu() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = FrameLayout(this)
        root.addView(View(this).apply { setOnClickListener { hideMenu() } },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(3, Color.BLACK) }
            setPadding(24, 24, 24, 24)
            addView(TextView(this@BarService).apply {
                text = "Launcher demo"; setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD; setPadding(16, 8, 16, 24)
            })
            addView(row("Notesprout Dev") { launch(NOTESPROUT_DEV) })
            addView(row("Home") { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
            // Ratta's HOME activity is only a boot screen that re-opens the last app; the visible "home" is Notes.
            addView(row("Supernote Notes") { launch("com.ratta.supernote.note") })
            addView(row("Close") { })
        }
        root.addView(panel, FrameLayout.LayoutParams(520, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        runCatching { wm.addView(root, lp); overlay = root; log("overlay shown") }.onFailure { log("overlay failed: $it") }
    }

    private fun hideMenu() {
        val v = overlay ?: return
        overlay = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        log("overlay hidden")
    }

    private fun row(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(16, 28, 16, 28)
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(2, Color.BLACK) }
        setOnClickListener { hideMenu(); runCatching { onTap() }.onFailure { log("launch failed: $it") } }
    }

    private fun launch(pkg: String) {
        userLaunched = true
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { log("launch: $pkg not installed"); return }
        log("launch: $pkg"); startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- the firmware launcher's binder ------------------------------------------

    private fun transact(code: Int, arg: Int): Boolean {
        val b = gesture ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeInt(arg)
            b.transact(code, data, reply, 0); reply.readException(); true
        } catch (e: Exception) { log("binder $code threw ${e.javaClass.simpleName}"); false }
        finally { data.recycle(); reply.recycle() }
    }

    // Only the side menu is ours; the pull-down status bar (KEYCODE_DRAG from the top edge)
    // stays the firmware's, so its flag is never locked — and is unlocked on the way out in case
    // an older build left it shut.
    private fun lock(on: Boolean) {
        if (gesture == null) return
        val a = transact(4, if (on) 1 else 0)
        if (!on) transact(5, 0)
        Log.d(TAG, "lock($on) → $a")
    }

    private fun log(line: String) = Log.d(TAG, line)

    companion object {
        @Volatile var running = false
        private const val TAG = "LauncherDemoBars"
        private const val LAUNCHER = "com.ratta.supernote.launcher"
        private const val DESCRIPTOR = "com.ratta.supernote.launcher.IGestureInterface"
        private const val REFRESH = "com.ratta.supernote.launcher.flashscreen"
        private const val MENU_STATE = "com.ratta.supernote.launcher.slidebarstatusbarstate"
        private const val NOTESPROUT_DEV = "com.symmetricalpalmtree.notesproutsn.dev"
        private const val RATTA_NOTES = "com.ratta.supernote.note"
        private const val BOOT_WINDOW_MS = 180_000L
        private const val RIGHT_FIRST = 310
        private const val TAP_MS = 250L
        /** Observe only: the firmware must still see the swipe so its refresh tells us "up". */
        private const val CONSUME = false
    }
}
