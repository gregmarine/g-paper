package com.symmetricalpalmtree.gpaper.core.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNativeBitmap
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasPaperViewCacheTest {
    @Config(shadows = [HardwareBitmapShadow::class])
    @Test fun hardwareTemplateCanReplaceACachedSoftwareTemplate() {
        val view = paper()
        val software = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val hardware = hardwareBitmap()
        assertEquals(Bitmap.Config.HARDWARE, hardware.config)
        try {
            view.setTemplate(software)
            assertEquals(Color.BLUE, view.displayPixel(100, 100))
            // setTemplate records a hardware RenderNode. It must not send this bitmap
            // through the cache's software Canvas, even when a cache already exists.
            view.setTemplate(hardware)
            view.commit(listOf(StrokePoint(100f, 100f)), allowGestures = false)
            view.setTemplate(software)
            assertEquals(Color.BLACK, view.displayPixel(100, 100))
            assertEquals(Color.BLUE, view.displayPixel(20, 20))
            assertFalse(hardware.isRecycled)
        } finally {
            view.release()
            hardware.recycle()
            software.recycle()
        }
    }

    @Config(shadows = [HardwareBitmapShadow::class])
    @Test fun hardwareTemplateLoadedBeforeLayoutCanBeRecorded() {
        val view = TestPaperView(RuntimeEnvironment.getApplication())
        val hardware = hardwareBitmap()
        try {
            view.setTemplate(hardware)
            view.layout(0, 0, 200, 200)
            assertFalse(hardware.isRecycled)
        } finally {
            view.release()
            hardware.recycle()
        }
    }

    @Test fun scribbleEraseRemovesCachedHostContentWithoutAnotherNotification() {
        val view = paper()
        val content = SquareContent()
        try {
            view.addContentRenderer(content)
            view.scribbleEraseEnabled = true
            view.setPaperListener(object : PaperListener {
                override fun onScribbleErased(strokeIds: List<String>, contentIds: List<String>) {
                    assertTrue(strokeIds.isEmpty())
                    assertEquals(listOf("square"), contentIds)
                    content.present = false // Contract: mutate and return, without notifyContentChanged.
                }
            })
            assertEquals(Color.BLACK, view.displayPixel(100, 100))
            val zigzag = (0 until 5).flatMap { pass ->
                (0..12).map { i ->
                    val x = if (pass % 2 == 0) 50f + i * 8f else 146f - i * 8f
                    StrokePoint(x, 80f + (pass * 13 + i) * 0.25f)
                }
            }
            assertFalse(view.commit(zigzag))
            assertFalse(content.present)
            assertEquals(Color.WHITE, view.displayPixel(100, 100))
        } finally {
            view.release()
        }
    }

    @Test fun lassoEraseRemovesCachedHostContentWithoutAnotherNotification() {
        val view = paper()
        val content = SquareContent()
        try {
            view.addContentRenderer(content)
            view.tool = Tool.LASSO_ERASER
            view.setPaperListener(object : PaperListener {
                override fun onLassoErased(strokeIds: List<String>, contentIds: List<String>) {
                    assertTrue(strokeIds.isEmpty())
                    assertEquals(listOf("square"), contentIds)
                    content.present = false
                }
            })
            assertEquals(Color.BLACK, view.displayPixel(100, 100))
            view.lasso(listOf(
                StrokePoint(30f, 30f), StrokePoint(170f, 30f),
                StrokePoint(170f, 170f), StrokePoint(30f, 170f), StrokePoint(30f, 30f),
            ))
            assertFalse(content.present)
            assertEquals(Color.WHITE, view.displayPixel(100, 100))
        } finally {
            view.release()
        }
    }

    @Test fun emptyAdditionStillPaintsDeferredStrokes() {
        val view = paper()
        try {
            // Populate the retained image before taking the same deferred-bake seam as Ratta.
            view.commit(listOf(StrokePoint(30f, 30f)), allowGestures = false)
            assertEquals(Color.BLACK, view.displayPixel(30, 30))
            view.deferBake = true
            view.commit(listOf(StrokePoint(100f, 100f)), allowGestures = false)
            view.addStrokes(emptyList())
            assertEquals(2, view.getStrokes().size)
            assertEquals(Color.BLACK, view.displayPixel(30, 30))
            assertEquals(Color.BLACK, view.displayPixel(100, 100))
        } finally {
            view.release()
        }
    }

    private fun paper() = TestPaperView(RuntimeEnvironment.getApplication()).apply {
        penWidth = 10f
        layout(0, 0, 200, 200)
    }

    private fun hardwareBitmap() = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).also {
        // Robolectric's native runtime backs HARDWARE copies with software pixels.
        // Mark only this fixture as hardware so Android's real Canvas validation runs;
        // all allocation, drawing, and cache behavior still uses native graphics.
        Shadow.extract<HardwareBitmapShadow>(it).hardware = true
    }

    @Implements(Bitmap::class, callNativeMethodsByDefault = true)
    class HardwareBitmapShadow : ShadowNativeBitmap() {
        @RealObject private lateinit var bitmap: Bitmap
        var hardware = false

        @Implementation fun getConfig(): Bitmap.Config? =
            if (hardware) Bitmap.Config.HARDWARE else reflector(BitmapAccess::class.java, bitmap).getConfig()
    }

    @ForType(Bitmap::class)
    interface BitmapAccess {
        @Direct fun getConfig(): Bitmap.Config?
    }

    private class TestPaperView(context: Context) : CanvasPaperView(context) {
        var deferBake = false

        override fun bakeAfterCommit() {
            if (!deferBake) super.bakeAfterCommit()
        }

        fun commit(points: List<StrokePoint>, allowGestures: Boolean = true) =
            commitCapturedStroke(points, allowGestures)

        fun lasso(points: List<StrokePoint>) = completeLassoOutline(points)

        fun displayPixel(x: Int, y: Int): Int {
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                // Exercise the display cache; renderToBitmap intentionally bypasses it.
                drawCommittedContent(Canvas(image), forDisplay = true)
                image.getPixel(x, y)
            } finally {
                image.recycle()
            }
        }
    }

    private class SquareContent : ContentRenderer {
        var present = true
        private val paint = Paint().apply { color = Color.BLACK }

        override fun draw(canvas: Canvas) {
            if (present) canvas.drawRect(40f, 40f, 160f, 160f, paint)
        }

        override fun hitTargets() =
            if (present) listOf(HitTarget("square", Bounds(40f, 40f, 160f, 160f))) else emptyList()
    }
}
