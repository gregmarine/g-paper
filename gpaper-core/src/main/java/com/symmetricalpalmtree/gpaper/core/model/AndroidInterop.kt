package com.symmetricalpalmtree.gpaper.core.model

import android.graphics.Rect
import android.graphics.RectF

/**
 * Bridging between the pure-Kotlin [Bounds] model and Android's rect types, for hosts
 * drawing in [com.symmetricalpalmtree.gpaper.core.render.ContentRenderer.draw] or
 * feeding chrome rects to `setExclusionRects`.
 */
fun Bounds.toRectF(): RectF = RectF(left, top, right, bottom)

/** [Bounds] → [Rect], rounded outward so the integer rect fully covers the float one. */
fun Bounds.toRectOut(): Rect = Rect(
    kotlin.math.floor(left).toInt(),
    kotlin.math.floor(top).toInt(),
    kotlin.math.ceil(right).toInt(),
    kotlin.math.ceil(bottom).toInt(),
)

fun RectF.toBounds(): Bounds = Bounds(left, top, right, bottom)

fun Rect.toBounds(): Bounds =
    Bounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
