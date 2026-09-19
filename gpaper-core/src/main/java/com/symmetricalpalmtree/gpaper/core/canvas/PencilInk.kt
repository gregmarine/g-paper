package com.symmetricalpalmtree.gpaper.core.canvas

/**
 * How one engine lays graphite: what colour the flecks are, whether they are opaque, and how
 * many of them catch.
 *
 * `PENCIL` is the one style whose appearance is a *texture* rather than a shape, and a texture
 * is the one thing a panel's own physics can argue with. The committed appearance of every
 * other style is core-rendered and identical everywhere; this is the seam where a device engine
 * says how the pencil must be drawn *on its panel* for the preview and the bake to be the same
 * mark — nothing more. It is a rendering decision, taken at draw time from the stroke's colour:
 * the [com.symmetricalpalmtree.gpaper.core.model.Stroke] the host persists keeps the shade the
 * artist picked, whatever an engine makes of it on the way to its glass.
 *
 * - [color] — the ink the flecks are painted in. Normally the stroke's own colour.
 * - [opaque] — flecks at full alpha (and aliased), darkness carried by size and count alone,
 *   rather than alpha-graded by [com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain]'s
 *   levels.
 * - [density] — the fraction of the grain that catches, `0`..`1` (see
 *   [com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain.of]). `1` is the full mark.
 *
 * The three travel together because on the one engine that needs them they are one decision:
 * Supernote's direct panel path paints **black opaque flecks and asks for fewer of them** for a
 * paler lead, because a 16-grey e-ink waveform reaches black at once and a grey only by passing
 * through it (Phase 28). Everywhere else — BOOX, the generic engine, `StrokeRasterizer`,
 * Paintsprout — the pencil is the stroke's colour, alpha-graded, at density 1.
 */
data class PencilInk(val color: Int, val opaque: Boolean, val density: Float)
