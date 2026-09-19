package com.symmetricalpalmtree.gpaper.ratta

/**
 * The `HTEINK_IOC_DISPAREA` argument (Phase 28): 24 bytes, little-endian, exactly as the
 * firmware's own `postEinkHostBmpRectFast` builds it —
 *
 * ```
 * struct { int32 left, top, right, bottom;   // PANEL coordinates
 *          int32 bufOffset;                  // byte offset of the source frame in the mapping
 *          uint8 mode;                       // 7 = the 16-grey path
 *          uint8 flag;                       // 1 = the pen-write flag Atelier uses
 *          uint8 pad[2]; }
 * ```
 *
 * Pure Kotlin with no Android imports, because a struct laid out wrong is a class of bug
 * that shows up as "the panel did something odd" and can otherwise only be found with a
 * device in hand. The layout is pinned by a JVM test against the bytes read out of
 * `libeinkutils.so` (`probe-ebc/README.md`).
 */
internal object EbcDisplayArg {

    /** Size of the struct in bytes. */
    const val SIZE = 24

    /** `HTEINK_IOC_GETINFO` — panel geometry; first call after `open`. */
    const val REQ_GETINFO = 0x48545201L

    /** `HTEINK_IOC_DISPAREA` — the display call. */
    const val REQ_DISPAREA = 0x48545701L

    /**
     * The 16-grey host path. Modes 4 / 8 / 9 draw something else with the same data
     * (shrunken or shifted copies — not decoded); **7 is the one that paints sixteen greys
     * cleanly, with no flash and no bake**, on the user's eyes on both devices.
     */
    const val MODE_GREY16 = 7

    /**
     * Atelier's own flag (`repaint::display_rect_after_set` in libspaint: mode 7, frame 0,
     * flag 1). Flags 0 / 1 / 4 / 5 all looked the same to the eye on the Nomad, so this is
     * not a waveform switch — it is simply the call the vendor's own pencil makes, and
     * there is no reason to differ from it.
     */
    const val FLAG_ATELIER = 1

    /** Write the struct into [into] (at least [SIZE] bytes). */
    fun pack(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bufOffset: Int,
        mode: Int,
        flag: Int,
        into: ByteArray,
    ) {
        putInt(into, 0, left)
        putInt(into, 4, top)
        putInt(into, 8, right)
        putInt(into, 12, bottom)
        putInt(into, 16, bufOffset)
        into[20] = mode.toByte()
        into[21] = flag.toByte()
        into[22] = 0
        into[23] = 0
    }

    private fun putInt(into: ByteArray, at: Int, value: Int) {
        into[at] = (value and 0xFF).toByte()
        into[at + 1] = ((value ushr 8) and 0xFF).toByte()
        into[at + 2] = ((value ushr 16) and 0xFF).toByte()
        into[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
