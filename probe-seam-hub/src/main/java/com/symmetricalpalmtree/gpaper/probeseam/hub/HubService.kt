package com.symmetricalpalmtree.gpaper.probeseam.hub

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import com.symmetricalpalmtree.gpaper.probeseam.ISeam
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * The hub half of the seam probe: one encrypted database, every read and write done here,
 * rows and rasters handed across in shared memory. Every call checks the caller's signing
 * certificate against this app's own, on top of the manifest's signature permission.
 */
class HubService : Service() {
    private val db: SQLiteDatabase by lazy { open() }

    private fun open(): SQLiteDatabase {
        System.loadLibrary("sqlcipher")
        val keyFile = File(filesDir, "key")
        if (!keyFile.exists()) {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            keyFile.writeText(raw.joinToString("") { "%02x".format(it) })
        }
        val t0 = System.nanoTime()
        val d = SQLiteDatabase.openOrCreateDatabase(File(filesDir, "probe.soil"), "x'${keyFile.readText()}'", null, null, null)
        d.execSQL("CREATE TABLE IF NOT EXISTS stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL, \"order\" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, style TEXT NOT NULL, blob BLOB NOT NULL)")
        d.execSQL("CREATE INDEX IF NOT EXISTS stroke_page_order ON stroke(pageId, \"order\")")
        d.execSQL("CREATE TABLE IF NOT EXISTS raster (id TEXT PRIMARY KEY, blob BLOB NOT NULL)")
        Log.i(TAG, "store open in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return d
    }

    private val books = HashMap<String, SQLiteDatabase>()

    /** Plaintext exports dropped in files/import become encrypted files in files/garden, once. */
    private fun garden(): File {
        System.loadLibrary("sqlcipher")
        db // makes the key
        val key = File(filesDir, "key").readText()
        val garden = File(filesDir, "garden").apply { mkdirs() }
        File(filesDir, "import").listFiles()?.filter { it.name.endsWith(".soil") }?.forEach { src ->
            val dst = File(garden, src.name)
            if (!dst.exists()) {
                val t0 = System.nanoTime()
                val plain = SQLiteDatabase.openOrCreateDatabase(src, "", null, null, null)
                plain.execSQL("ATTACH DATABASE '${dst.path}' AS enc KEY \"x'$key'\"")
                plain.rawQuery("SELECT sqlcipher_export('enc')").use { it.moveToFirst() }
                plain.execSQL("DETACH DATABASE enc")
                plain.close()
                Log.i(TAG, "encrypted ${src.name} (${src.length()} B) in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            src.delete()
        }
        return garden
    }

    private fun book(name: String): SQLiteDatabase = synchronized(books) {
        books.getOrPut(name) {
            require(!name.contains('/')) { "not a notebook name" }
            val t0 = System.nanoTime()
            SQLiteDatabase.openOrCreateDatabase(File(garden(), name), "x'${File(filesDir, "key").readText()}'", null, null, null)
                .also { Log.i(TAG, "opened $name in ${(System.nanoTime() - t0) / 1_000_000} ms") }
        }
    }

    /** id(36) color(4) width(4) styleLen(1) style len(4) blob — per row, in writing order. */
    private fun readNotebookPage(name: String, pageId: String): ByteArray {
        val rows = ArrayList<ByteArray>()
        var total = 0
        book(name).rawQuery("SELECT id, color, strokeWidth, style, blob FROM notebook WHERE parentId = ? AND type = 'stroke' AND deletedAt IS NULL AND blob IS NOT NULL ORDER BY \"order\"", arrayOf(pageId)).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(4)
                val style = (c.getString(3) ?: "PEN").toByteArray(Charsets.US_ASCII)
                val color = runCatching { android.graphics.Color.parseColor(c.getString(1)) }.getOrDefault(-0x1000000)
                val row = ByteBuffer.allocate(49 + style.size + blob.size)
                row.put(c.getString(0).toByteArray(Charsets.US_ASCII)).putInt(color).putFloat(c.getFloat(2))
                    .put(style.size.toByte()).put(style).putInt(blob.size).put(blob)
                rows += row.array(); total += row.capacity()
            }
        }
        val out = ByteBuffer.allocate(4 + total).putInt(rows.size)
        rows.forEach { out.put(it) }
        return out.array()
    }

    private fun check() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (packageManager.checkSignatures(Process.myUid(), uid) != PackageManager.SIGNATURE_MATCH) {
            Log.w(TAG, "refused uid $uid — not our certificate")
            throw SecurityException("caller is not signed with the hub's certificate")
        }
    }

    /** id(36) order(8) color(4) width(4) style(1) len(4) blob — per row. */
    private fun readPage(pageId: String): ByteArray {
        val rows = ArrayList<ByteArray>()
        var total = 0
        db.rawQuery("SELECT id, \"order\", color, width, style, blob FROM stroke WHERE pageId = ? ORDER BY \"order\"", arrayOf(pageId)).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(5)
                val row = ByteBuffer.allocate(57 + blob.size)
                row.put(c.getString(0).toByteArray(Charsets.US_ASCII)).putLong(c.getLong(1)).putInt(c.getInt(2))
                    .putFloat(c.getFloat(3)).put(if (c.getString(4) == "PEN") 0 else 1).putInt(blob.size).put(blob)
                rows += row.array(); total += row.capacity()
            }
        }
        val out = ByteBuffer.allocate(4 + total).putInt(rows.size)
        rows.forEach { out.put(it) }
        return out.array()
    }

    private fun share(bytes: ByteArray): SharedMemory {
        val m = SharedMemory.create("seam", bytes.size.coerceAtLeast(1))
        val b = m.mapReadWrite(); b.put(bytes); SharedMemory.unmap(b)
        m.setProtect(OsConstants.PROT_READ)
        return m
    }

    private fun rasterBytes(id: String, offset: Int, length: Int): ByteArray =
        db.rawQuery("SELECT substr(blob, ?, ?) FROM raster WHERE id = ?", offset + 1, length, id).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else ByteArray(0)
        }

    private val seam = object : ISeam.Stub() {
        override fun ping(): Int { check(); return 1 }

        override fun seed(strokes: Int, blobBytes: Int): String {
            check()
            val page = UUID.randomUUID().toString()
            val rnd = SecureRandom()
            db.beginTransaction()
            try {
                for (i in 0 until strokes) {
                    val blob = ByteArray(blobBytes).also { rnd.nextBytes(it) }
                    db.execSQL("INSERT INTO stroke VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>(UUID.randomUUID().toString(), page, i.toLong(), 0xFF000000.toInt(), 2.0, "PEN", blob))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            return page
        }

        override fun loadPage(pageId: String): SharedMemory { check(); return share(readPage(pageId)) }

        override fun baselineLoadNanos(pageId: String): Long {
            check()
            val t0 = System.nanoTime(); readPage(pageId); return System.nanoTime() - t0
        }

        override fun saveStrokes(pageId: String, rows: SharedMemory, count: Int): Int {
            check()
            val b = rows.mapReadOnly()
            db.beginTransaction()
            try {
                repeat(count) {
                    val id = ByteArray(36).also { b.get(it) }.toString(Charsets.US_ASCII)
                    val order = b.getLong(); val color = b.getInt(); val width = b.getFloat(); val style = b.get()
                    val blob = ByteArray(b.getInt()).also { b.get(it) }
                    db.execSQL("INSERT OR REPLACE INTO stroke VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>(id, pageId, order, color, width.toDouble(), if (style.toInt() == 0) "PEN" else "PENCIL", blob))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction(); SharedMemory.unmap(b); rows.close() }
            return count
        }

        override fun putRaster(id: String, bytes: SharedMemory) {
            check()
            val b = bytes.mapReadOnly()
            val a = ByteArray(bytes.size).also { b.get(it) }
            SharedMemory.unmap(b); bytes.close()
            db.execSQL("INSERT OR REPLACE INTO raster VALUES (?, ?)", arrayOf<Any>(id, a))
        }

        override fun getRaster(id: String): SharedMemory {
            check()
            val size = db.rawQuery("SELECT length(blob) FROM raster WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val out = ByteBuffer.allocate(size)
            var off = 0
            while (off < size) {
                val part = rasterBytes(id, off, PIECE)
                check(part.isNotEmpty()) { "raster piece at $off came back empty" }
                out.put(part); off += part.size
            }
            return share(out.array())
        }

        override fun notebooks(): Array<String> {
            check(); return garden().list()?.filter { it.endsWith(".soil") }?.sorted()?.toTypedArray() ?: emptyArray()
        }

        override fun pages(notebook: String): Array<String> {
            check()
            val out = ArrayList<String>()
            book(notebook).rawQuery("SELECT id, width, height FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY \"order\"", arrayOf<String>()).use { c ->
                while (c.moveToNext()) out += "${c.getString(0)}|${c.getInt(1)}|${c.getInt(2)}"
            }
            return out.toTypedArray()
        }

        override fun loadNotebookPage(notebook: String, pageId: String): SharedMemory { check(); return share(readNotebookPage(notebook, pageId)) }

        override fun baselinePageNanos(notebook: String, pageId: String): Long {
            check()
            val t0 = System.nanoTime(); readNotebookPage(notebook, pageId); return System.nanoTime() - t0
        }

        override fun getRasterChunk(id: String, offset: Int, length: Int): ByteArray { check(); return rasterBytes(id, offset, length) }
    }

    override fun onBind(intent: Intent): IBinder = seam

    private companion object {
        const val TAG = "SeamProbeHub"
        const val PIECE = 256 * 1024 // stays inside one cursor window
    }
}
