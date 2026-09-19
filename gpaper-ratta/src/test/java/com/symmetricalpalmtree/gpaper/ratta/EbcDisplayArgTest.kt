package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 24-byte DISPAREA struct, pinned against the layout read out of `libeinkutils.so`
 * (`probe-ebc/README.md`): five little-endian int32s, then `mode`, `flag`, two pad bytes.
 *
 * A struct packed wrong is not a crash — the driver reads whatever the bytes say and
 * refreshes some other rectangle, or none — so it is exactly the kind of fault that costs a
 * device session to find and nothing at all to pin here.
 */
class EbcDisplayArgTest {

    private fun pack(
        left: Int = 10,
        top: Int = 20,
        right: Int = 30,
        bottom: Int = 40,
        bufOffset: Int = 0,
        mode: Int = EbcDisplayArg.MODE_GREY16,
        flag: Int = EbcDisplayArg.FLAG_ATELIER,
    ): ByteArray {
        val out = ByteArray(EbcDisplayArg.SIZE) { 0x5A }
        EbcDisplayArg.pack(left, top, right, bottom, bufOffset, mode, flag, out)
        return out
    }

    private fun intAt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    @Test
    fun `the struct is twenty-four bytes`() {
        assertEquals(24, EbcDisplayArg.SIZE)
    }

    @Test
    fun `the four edges are little-endian int32s in order`() {
        val b = pack(left = 0x01020304, top = 0x11121314, right = 0x21222324, bottom = 0x31323334)
        assertEquals(0x01020304, intAt(b, 0))
        assertEquals(0x11121314, intAt(b, 4))
        assertEquals(0x21222324, intAt(b, 8))
        assertEquals(0x31323334, intAt(b, 12))
        // Little-endian, explicitly — the low byte first, not the high one.
        assertEquals(0x04.toByte(), b[0])
        assertEquals(0x01.toByte(), b[3])
    }

    @Test
    fun `the buffer offset is the fifth int and the mode and flag are single bytes after it`() {
        val b = pack(bufOffset = 0x00A0B0C0, mode = 7, flag = 1)
        assertEquals(0x00A0B0C0, intAt(b, 16))
        assertEquals(7.toByte(), b[20])
        assertEquals(1.toByte(), b[21])
    }

    @Test
    fun `the two pad bytes are zeroed, whatever was in the buffer before`() {
        val b = pack()
        assertEquals(0.toByte(), b[22])
        assertEquals(0.toByte(), b[23])
    }

    @Test
    fun `the call this path makes is Atelier's own`() {
        // Mode 7 is the sixteen-grey one (4 / 8 / 9 draw something else with the same data);
        // flag 1 is what libspaint's repaint::display_rect_after_set passes. Neither is a
        // choice we made, and changing either means another walk on a device.
        assertEquals(7, EbcDisplayArg.MODE_GREY16)
        assertEquals(1, EbcDisplayArg.FLAG_ATELIER)
        assertEquals(0x48545701L, EbcDisplayArg.REQ_DISPAREA)
        assertEquals(0x48545201L, EbcDisplayArg.REQ_GETINFO)
    }
}
