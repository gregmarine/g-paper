package com.symmetricalpalmtree.gpaper.ratta

/**
 * A rect with holes cut out of it, as the rects that remain — pure, so the one thing that
 * would only show as chrome overwritten on a panel is settled on the JVM (Phase 42).
 *
 * The firmware daemon respects the host's disable areas: it paints nothing inside a chrome
 * zone however the pen crosses it. On a direct page the engine is the painter, and a post
 * whose rect overlaps a zone — the padded rect of a segment drawn right up to a bar — would
 * write page pixels over that bar on the panel until the compositor next repaints it. So
 * every post is cut around the exclusion rects first, and each piece goes out on its own.
 *
 * Rects are `l, t, r, b` quads (half-open) in one [IntArray], four ints a rect. The pieces
 * of a subtraction never overlap one another and cover exactly the rect minus the holes;
 * a hole that misses the rect costs nothing and a hole covering it leaves nothing.
 */
internal object PanelClip {

    /**
     * [rect] (`l, t, r, b`) minus every rect in [holes] (`l, t, r, b` × n), as quads. An
     * empty [rect] is nothing; empty or degenerate holes are ignored.
     */
    fun subtract(rect: IntArray, holes: IntArray): IntArray {
        var pieces = intArrayOf(rect[0], rect[1], rect[2], rect[3])
        if (pieces[2] <= pieces[0] || pieces[3] <= pieces[1]) return IntArray(0)
        var h = 0
        while (h + 3 < holes.size) {
            val hl = holes[h]
            val ht = holes[h + 1]
            val hr = holes[h + 2]
            val hb = holes[h + 3]
            h += 4
            if (hr <= hl || hb <= ht) continue
            pieces = cut(pieces, hl, ht, hr, hb)
            if (pieces.isEmpty()) return pieces
        }
        return pieces
    }

    /** Every piece minus one hole: a piece the hole misses stays whole; one it covers
     *  goes; one it crosses becomes up to four bands — above, below, left, right. */
    private fun cut(pieces: IntArray, hl: Int, ht: Int, hr: Int, hb: Int): IntArray {
        val out = ArrayList<Int>(pieces.size + 12)
        var i = 0
        while (i + 3 < pieces.size) {
            val l = pieces[i]
            val t = pieces[i + 1]
            val r = pieces[i + 2]
            val b = pieces[i + 3]
            i += 4
            if (hr <= l || hl >= r || hb <= t || ht >= b) {
                out.add(l); out.add(t); out.add(r); out.add(b)
                continue
            }
            // Above and below the hole take the piece's full width; the middle band's
            // left and right take what is beside the hole.
            if (ht > t) { out.add(l); out.add(t); out.add(r); out.add(ht) }
            if (hb < b) { out.add(l); out.add(hb); out.add(r); out.add(b) }
            val mt = maxOf(t, ht)
            val mb = minOf(b, hb)
            if (hl > l) { out.add(l); out.add(mt); out.add(hl); out.add(mb) }
            if (hr < r) { out.add(hr); out.add(mt); out.add(r); out.add(mb) }
        }
        return out.toIntArray()
    }
}
