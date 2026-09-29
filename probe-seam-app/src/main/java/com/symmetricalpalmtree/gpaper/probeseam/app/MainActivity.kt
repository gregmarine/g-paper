package com.symmetricalpalmtree.gpaper.probeseam.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.symmetricalpalmtree.gpaper.probeseam.ISeam
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.concurrent.thread

/** Binds the hub, runs the timing suite once, and prints it (logcat tag SeamProbe). */
class MainActivity : Activity() {
    private lateinit var text: TextView
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val seam = ISeam.Stub.asInterface(service)
            thread { runCatching { suite(seam) }.onFailure { say("FAILED: $it") } }
        }
        override fun onServiceDisconnected(name: ComponentName) { say("hub gone") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(text) })
        val hub = Intent().setClassName("com.symmetricalpalmtree.gpaper.probeseam.hub", "com.symmetricalpalmtree.gpaper.probeseam.hub.HubService")
        val bound = runCatching { bindService(hub, conn, Context.BIND_AUTO_CREATE) }
        say("bind → ${bound.getOrNull() ?: bound.exceptionOrNull()}")
    }

    override fun onDestroy() { runCatching { unbindService(conn) }; super.onDestroy() }

    private fun say(line: String) { Log.i(TAG, line); runOnUiThread { text.append(line + "\n") } }

    private fun time(reps: Int, block: () -> Unit): String {
        val ms = (0 until reps).map { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }.sorted()
        return "median %.1f ms (min %.1f, max %.1f)".format(ms[ms.size / 2], ms.first(), ms.last())
    }

    private fun rows(count: Int, blobBytes: Int, firstOrder: Long): SharedMemory {
        val m = SharedMemory.create("rows", count * (57 + blobBytes))
        val b = m.mapReadWrite()
        repeat(count) { i ->
            b.put(UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)).putLong(firstOrder + i).putInt(-0x1000000)
                .putFloat(2f).put(0).putInt(blobBytes).put(ByteArray(blobBytes) { it.toByte() })
        }
        SharedMemory.unmap(b)
        return m
    }

    private fun decode(m: SharedMemory): Int {
        val b: ByteBuffer = m.mapReadOnly()
        val n = b.getInt()
        repeat(n) {
            b.position(b.position() + 53)
            ByteArray(b.getInt()).also { b.get(it) } // copied out, as a real decode would
        }
        SharedMemory.unmap(b); m.close()
        return n
    }

    private fun suite(seam: ISeam) {
        say("first call (opens the store): " + time(1) { seam.ping() })
        say("empty call: " + time(REPS) { seam.ping() })
        for (strokes in intArrayOf(200, 1000, 3000)) {
            val page = seam.seed(strokes, BLOB)
            say("— page of $strokes strokes, $BLOB B each —")
            say("  load through the seam: " + time(REPS) { check(decode(seam.loadPage(page)) == strokes) })
            val base = (0 until REPS).map { seam.baselineLoadNanos(page) / 1e6 }.sorted()
            say("  same read inside the hub: median %.1f ms".format(base[base.size / 2]))
            var order = strokes.toLong()
            say("  save 1 stroke: " + time(REPS) { seam.saveStrokes(page, rows(1, BLOB, order), 1); order++ })
            say("  save 50 strokes: " + time(REPS) { seam.saveStrokes(page, rows(50, BLOB, order), 50); order += 50 })
        }
        for (mib in intArrayOf(1, 3)) {
            val size = mib * 1024 * 1024
            say("— raster of $mib MiB —")
            say("  save in one piece: " + time(REPS) {
                val m = SharedMemory.create("raster", size)
                val b = m.mapReadWrite(); b.put(ByteArray(size) { (it * 31).toByte() }); SharedMemory.unmap(b)
                seam.putRaster("r$mib", m)
            })
            say("  load in one piece: " + time(REPS) {
                val m = seam.getRaster("r$mib"); val b = m.mapReadOnly()
                check(ByteArray(size).also { b.get(it) }[5] == (5 * 31).toByte()); SharedMemory.unmap(b); m.close()
            })
            say("  load in 128 KiB chunks: " + time(REPS) {
                var off = 0
                while (off < size) { val n = seam.getRasterChunk("r$mib", off, CHUNK).size; check(n > 0); off += n }
            })
        }
        say("DONE")
    }

    private companion object {
        const val TAG = "SeamProbe"
        const val REPS = 15
        const val BLOB = 1024
        const val CHUNK = 128 * 1024
    }
}
