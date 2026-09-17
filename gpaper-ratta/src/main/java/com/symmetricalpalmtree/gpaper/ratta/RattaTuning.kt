package com.symmetricalpalmtree.gpaper.ratta

/**
 * **A measurement door, not host API** (0.1.35 — re-opened for the Manta).
 *
 * 0.1.34 froze the raster page's numbers as the Nomad measured them. On the Manta the
 * baked `PENCIL` hairline lands visibly wider than the firmware's live line: the bake is
 * the same pixels on both panels, so it is the Manta's firmware that paints EMR 120
 * thinner than the Nomad's does. These two values are what a walk needs to find the
 * Manta's own answer with `setprop` and a tool flip instead of a rebuild per candidate.
 *
 * **Hosts leave every value at its default** (`null` — the engine's own constants). This
 * is not a configuration surface, it carries no compatibility promise, and it is removed
 * again once the Manta's numbers freeze into per-model constants.
 *
 * Read on the main thread at pen-arming and at each bake; set them before the paper view
 * is created, or flip a tool afterwards so the firmware is re-armed.
 */
object RattaTuning {

    /**
     * The firmware EMR size `PENCIL`'s live ink is armed at, or `null` for
     * [RattaEmr.penSize]'s own answer (120 for the 1.2 px lead).
     */
    @JvmStatic
    @Volatile
    var pencilEmr: Int? = null

    /**
     * The constant pressure `PENCIL` bakes at on a raster page, or `null` for the frozen
     * Nomad measurement (0.5).
     */
    @JvmStatic
    @Volatile
    var pencilBakePressure: Float? = null
}
