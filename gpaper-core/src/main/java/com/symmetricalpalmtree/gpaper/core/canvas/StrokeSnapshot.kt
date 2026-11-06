package com.symmetricalpalmtree.gpaper.core.canvas

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import java.util.RandomAccess

/** Immutable, any-thread snapshots that share completed blocks when a stroke is appended. */
internal class StrokeSnapshot private constructor(private val root: Node?) : AbstractList<Stroke>(), RandomAccess {
    override val size: Int get() = root?.size ?: 0

    override fun get(index: Int): Stroke {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index of $size")
        return root!!.get(index)
    }

    fun append(stroke: Stroke): StrokeSnapshot = StrokeSnapshot(root?.append(stroke) ?: Leaf(arrayOf(stroke)))

    private sealed class Node(val size: Int) {
        abstract fun get(index: Int): Stroke
        abstract fun append(stroke: Stroke): Node
    }

    private class Leaf(val values: Array<Stroke>) : Node(values.size) {
        override fun get(index: Int): Stroke = values[index]
        override fun append(stroke: Stroke): Node =
            if (size < 32) Leaf(values + stroke) else Branch(this, Leaf(arrayOf(stroke)))
    }

    private class Branch(val left: Node, val right: Node) : Node(left.size + right.size) {
        override fun get(index: Int): Stroke = if (index < left.size) left.get(index) else right.get(index - left.size)
        override fun append(stroke: Stroke): Node =
            if (right.size < left.size) Branch(left, right.append(stroke)) else Branch(this, Leaf(arrayOf(stroke)))
    }

    override fun iterator(): Iterator<Stroke> = object : Iterator<Stroke> {
        private val pending = ArrayDeque<Node>().apply { root?.let(::addLast) }
        private var leaf = emptyArray<Stroke>()
        private var index = 0

        override fun hasNext(): Boolean = index < leaf.size || pending.isNotEmpty()

        override fun next(): Stroke {
            while (index == leaf.size) {
                when (val node = pending.removeLastOrNull() ?: throw NoSuchElementException()) {
                    is Branch -> {
                        pending.addLast(node.right)
                        pending.addLast(node.left)
                    }
                    is Leaf -> {
                        leaf = node.values
                        index = 0
                    }
                }
            }
            return leaf[index++]
        }
    }

    companion object {
        fun of(strokes: List<Stroke>): StrokeSnapshot {
            var result = StrokeSnapshot(null)
            for (stroke in strokes) result = result.append(stroke)
            return result
        }
    }
}
