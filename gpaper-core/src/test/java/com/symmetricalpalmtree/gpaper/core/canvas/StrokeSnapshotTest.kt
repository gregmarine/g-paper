package com.symmetricalpalmtree.gpaper.core.canvas

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import org.junit.Assert.*
import org.junit.Test

class StrokeSnapshotTest {
    @Test fun appendsPreservePublishedSnapshotsAndOrderAcrossTreeBoundaries() {
        var current = StrokeSnapshot.of(emptyList())
        val saved = mutableListOf<StrokeSnapshot>()
        repeat(4097) { i ->
            if (i in listOf(0, 1, 31, 32, 33, 63, 64, 65, 1024, 4096)) saved += current
            current = current.append(Stroke("$i", emptyList()))
        }
        for (snapshot in saved + listOf(current)) {
            assertEquals((0 until snapshot.size).map(Int::toString), snapshot.map { it.id })
            for (i in snapshot.indices) assertEquals("$i", snapshot[i].id)
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot[snapshot.size] }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot[-1] }
        }
    }
}
