package com.symmetricalpalmtree.gpaper.ratta

import android.content.Context
import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Binder client for the Supernote (Ratta) firmware's stylus ink daemon — the Ratta
 * analogue of the Onyx SDK overlay, minus any SDK: the firmware registers a Binder
 * service (`service_myservice`, legacy alias `service.myservice`) with interface token
 * `android.demo.IMyService`, driven by raw [Parcel] transactions. Ported from the
 * hardware-validated Notesprout client (itself from the KOReader `supernote_ink.lua`
 * lineage), verified on Nomad + Manta, firmware `Chauvet.E103…2389` — the ink path
 * survived a firmware update mid-test.
 *
 * The app claims pen ownership, configures the active pen (type code, EMR size, colour
 * code), sends disable areas (screen coordinates — rects where the firmware must NOT
 * paint; a full-screen rect is also the only "firmware off" switch, there is no disable
 * transaction), and clears the EPDC ink overlay. The firmware paints live stroke pixels
 * to the e-ink overlay at sub-frame latency, composited ABOVE the framebuffer, and
 * returns **no point data** — points arrive through the normal MotionEvent stream.
 *
 * Every method is a safe no-op when the firmware binder is absent, so callers can invoke
 * these unconditionally. Failures are loud: logged at [Log.w] (release-visible) and
 * fired through [onFailure] — never silently papered over, never an engine swap.
 */
internal object SupernoteInk {
    private const val TAG = "GPaperRatta"

    private const val IFACE_TOKEN = "android.demo.IMyService"
    private const val APP_NAME = "gpaper"
    private val SERVICE_NAMES = arrayOf("service_myservice", "service.myservice")

    // Firmware transaction codes (from the decompiled HandWriteClient).
    private const val TX_WRITE_APP_INFO = 0
    private const val TX_DISABLE_AREA = 1
    private const val TX_PEN = 2
    private const val TX_DRAW_BUFFER = 6

    /**
     * Pen type codes for the firmware's penTypeArray, from the 0…31 sweep (Nomad +
     * Manta): 0/5/8/10/11 solid steady; 1/2/16 pressure-sensitive (17–31 alias to 16);
     * 3 = stream of tiny x's; 4 = stream of dashes; 14/15 calligraphy; **12 is broken**
     * (random giant laggy blob — never arm it); 6/7/9/13 render nothing.
     */
    object Pen {
        /** Uniform-width ballpoint — matches a uniform-width baked polyline. */
        const val NEEDLE = 10

        /** Pressure-sensitive ink pen. */
        const val INK = 16

        /** Stream of dashes — the Supernote lasso-selector trail appearance. */
        const val DASH = 4

        /** Stream of tiny x's — the Supernote lasso-eraser trail appearance. */
        const val CROSS = 3

        /** Calligraphy (code 15; 14 is the possible-calligraphy fallback). */
        const val CALLIGRAPHY = 15
    }

    /**
     * Firmware colour codes. They render far lighter than named (see [RattaInkMap]) —
     * the eraser semantics live in the colour-255 payload of [setEraser], not here.
     */
    object Color {
        const val BLACK = 0
        const val DARK_GRAY = -101
        const val GRAY = -102
        const val LIGHT_GRAY = 254
    }

    /**
     * Fired (with a short human-readable message) on any firmware failure so the engine
     * can surface it — the "post-construction failures must be loud" contract.
     */
    @Volatile
    var onFailure: ((String) -> Unit)? = null

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        onFailure?.invoke(msg)
    }

    private var binder: IBinder? = null

    // Tri-state: null = untested, false = absent, true = present.
    private var available: Boolean? = null

    /** Whether the firmware ink daemon is reachable. Cached after the first lookup. */
    @Synchronized
    fun isAvailable(): Boolean {
        available?.let { return it }
        binder = lookupBinder()
        val ok = binder != null
        available = ok
        if (!ok) Log.i(TAG, "service_myservice not present; firmware ink unavailable")
        return ok
    }

    private fun lookupBinder(): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            for (name in SERVICE_NAMES) {
                val b = getService.invoke(null, name) as? IBinder
                if (b != null) {
                    Log.i(TAG, "found firmware binder \"$name\"")
                    return b
                }
            }
            null
        } catch (t: Throwable) {
            fail("binder lookup failed: ${t.message}")
            null
        }
    }

    /**
     * Run one transaction. [writeArgs] writes the per-call int payload after the
     * "interface token + app name" preamble every transaction shares.
     */
    @Synchronized
    private fun transact(code: Int, writeArgs: (Parcel) -> Unit) {
        if (!isAvailable()) return
        var b = binder
        if (b == null || !b.isBinderAlive) {
            // Firmware service may have restarted; re-look-up once.
            b = lookupBinder()
            binder = b
            if (b == null) {
                available = false
                fail("firmware binder gone, marking unavailable")
                return
            }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_TOKEN)
            data.writeString(APP_NAME)
            writeArgs(data)
            b.transact(code, data, reply, 0)
        } catch (t: Throwable) {
            // DeadObjectException etc. — drop the cached proxy so the next call re-looks up.
            fail("transact($code) failed: ${t.message}")
            binder = null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** tx=0 WRITE_APP_INFO — claim pen ownership for this app. */
    fun claimPen() {
        transact(TX_WRITE_APP_INFO) {
            it.writeInt(0)
            it.writeInt(0)
        }
    }

    /** tx=2 PEN — set active pen type, EMR size, and colour code. */
    fun setPen(type: Int, sizeEmr: Int, color: Int) {
        transact(TX_PEN) {
            it.writeInt(type)
            it.writeInt(sizeEmr)
            it.writeInt(color)
        }
    }

    /**
     * tx=2 PEN — configure the firmware eraser (type 1 = round, 3 = rectangular). The
     * colour-255 payload is what makes it an eraser: the firmware stops painting ink
     * along the path; stroke removal itself stays software-side.
     */
    fun setEraser(rectangular: Boolean, sizeEmr: Int) {
        transact(TX_PEN) {
            it.writeInt(if (rectangular) 3 else 1)
            it.writeInt(sizeEmr)
            it.writeInt(255)
        }
    }

    /** tx=6 DRAW_BUFFER — clear the EPDC ink overlay. Overlay law 1: the panel drops the
     *  pixels only when an app frame co-presents — always pair with an `invalidate()`. */
    fun clearAll() {
        transact(TX_DRAW_BUFFER) {
            it.writeInt(255)
            it.writeInt(0)
        }
    }

    /** tx=1 DISABLE_AREA — one full-screen rect: the "firmware off" switch. */
    fun setFullScreenDisable(width: Int, height: Int) {
        transact(TX_DISABLE_AREA) {
            it.writeInt(1)          // rect count
            it.writeInt(0)          // x
            it.writeInt(0)          // y
            it.writeInt(width)
            it.writeInt(height)
            it.writeInt(0)          // reserved / flags
        }
    }

    /** tx=1 DISABLE_AREA — keep firmware ink off these rects (screen coordinates).
     *  One transaction accepts at least five rects. */
    fun setDisableAreas(rects: List<Rect>) {
        transact(TX_DISABLE_AREA) { p ->
            p.writeInt(rects.size)
            for (r in rects) {
                p.writeInt(r.left)
                p.writeInt(r.top)
                p.writeInt(r.width())
                p.writeInt(r.height())
                p.writeInt(0)
            }
        }
    }

    /** tx=1 DISABLE_AREA — clear all disable areas (firmware may paint everywhere). */
    fun clearDisableAreas() {
        transact(TX_DISABLE_AREA) {
            it.writeInt(0)          // zero rects
        }
    }

    private fun einkService(context: Context): Any? =
        try {
            context.getSystemService("eink")
        } catch (t: Throwable) {
            null
        }

    /**
     * Reflection on `getSystemService("eink").enableFullUiAuto(boolean)` — required for
     * a third-party app to get firmware ink painted at all (outside whitelisted firmware
     * apps). The eink service is `android.os.EinkManager` on the verified firmware; the
     * guard protects against future builds.
     */
    fun enableFullUiAuto(context: Context, enable: Boolean) {
        if (!isAvailable()) return
        try {
            val eink = einkService(context) ?: run {
                fail("eink system service not present")
                return
            }
            val m = eink.javaClass.getMethod("enableFullUiAuto", Boolean::class.javaPrimitiveType)
            m.invoke(eink, enable)
            Log.i(TAG, "enableFullUiAuto($enable) ok")
        } catch (t: Throwable) {
            fail("enableFullUiAuto unavailable: ${t.message}")
        }
    }

    /**
     * Enable Regal (anti-ghosting waveform) — set once at session setup; it is what
     * keeps overlay handoffs ghost-free without any per-handoff screen refresh (never
     * call a refresh per stroke or handoff — it flashes). [level] semantics are unknown;
     * 0 is the validated default.
     */
    fun enableAutoRegal(context: Context, enable: Boolean, level: Int = 0) {
        if (!isAvailable()) return
        val eink = einkService(context) ?: return
        try {
            eink.javaClass.getMethod(
                "enableAutoRegal",
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(eink, enable, level)
        } catch (t: Throwable) {
            fail("enableAutoRegal failed: ${t.message}")
        }
    }
}
