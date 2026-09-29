// Seam probe (2026-09-28) — the calls a feature app would make on a hub that owns every
// encrypted database. Large data rides in SharedMemory, never inline in the transaction.
package com.symmetricalpalmtree.gpaper.probeseam;

import android.os.SharedMemory;

interface ISeam {
    int ping();
    /** Make a page of `strokes` rows, each with a `blobBytes` blob; answers its id. */
    String seed(int strokes, int blobBytes);
    /** Every stroke row of the page, encoded, in one block. */
    SharedMemory loadPage(String pageId);
    /** The same read done inside the hub with no seam — nanoseconds. */
    long baselineLoadNanos(String pageId);
    /** Write `count` encoded stroke rows in one transaction; answers rows written. */
    int saveStrokes(String pageId, in SharedMemory rows, int count);
    void putRaster(String id, in SharedMemory bytes);
    SharedMemory getRaster(String id);
    /** The chunked road the sketch seam takes today, for comparison. */
    byte[] getRasterChunk(String id, int offset, int length);

    /** The notebooks the hub holds (each its own encrypted file). */
    String[] notebooks();
    /** The notebook's live pages in order, each "id|width|height". */
    String[] pages(String notebook);
    /** Every live stroke of one real page, encoded, in one block. */
    SharedMemory loadNotebookPage(String notebook, String pageId);
    /** The same read done inside the hub with no seam — nanoseconds. */
    long baselinePageNanos(String notebook, String pageId);
}
