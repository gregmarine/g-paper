package com.symmetricalpalmtree.gpaper.ratta

import android.util.Log
import java.nio.ByteBuffer

/**
 * The raw syscalls the panel driver needs (Phase 28). Negative returns are `-errno`.
 *
 * **Loading is allowed to fail and must never be fatal.** This module is published as an
 * ordinary AAR and a host may run it anywhere — a device with no arm64 slice, a repackaged
 * APK that stripped the library, an instrumentation harness. A missing `.so` throws
 * `UnsatisfiedLinkError` on the very first call, from whatever thread happened to make it,
 * and a drawing engine that crashes because an *optimisation* is unavailable has no defence.
 * So the load is guarded once, here, and [available] is the gate every caller checks: with
 * it false the Supernote pencil simply keeps the firmware needle preview it has had since
 * 0.1.32, which is a complete, shipped path rather than a degraded one.
 */
internal object EbcNative {

    private const val TAG = "GPaperRatta"

    /** `O_RDWR or O_CLOEXEC` — the flags the probe opened `/dev/ebc` with. */
    const val O_RDWR = 2
    const val O_CLOEXEC = 0x80000

    /** Whether `libgpaper_ebc.so` loaded. False → no call below may be made. */
    val available: Boolean = try {
        System.loadLibrary("gpaper_ebc")
        true
    } catch (t: Throwable) {
        // Not a warning per call site — one line, at load, saying which path the pencil is on.
        Log.i(TAG, "panel: needle — no pass-through (${t.javaClass.simpleName})")
        false
    }

    @JvmStatic external fun open(path: String, flags: Int): Int
    @JvmStatic external fun close(fd: Int): Int
    @JvmStatic external fun ioctl(fd: Int, req: Long, buf: ByteBuffer?): Int
    @JvmStatic external fun mmap(fd: Int, len: Long, off: Long): ByteBuffer?
    @JvmStatic external fun munmap(buf: ByteBuffer, len: Long): Int
    @JvmStatic external fun lastErrno(): Int
    @JvmStatic external fun strerror(err: Int): String
}
