package com.symmetricalpalmtree.gpaper.core.model

/**
 * The visual style a stroke is captured and rendered with — g-paper's abstract pen types.
 *
 * Deliberately **not** the device SDKs' native style codes: stroke data must be portable
 * (a page written on BOOX renders identically on Supernote or a generic tablet), so the
 * **committed appearance is defined by core's renderer** and is engine-independent. Each
 * engine maps the *live* ink to the closest style its firmware offers — the live stroke is
 * a best-effort preview; the baked stroke is the truth:
 *
 * The set is the union of what the target hardware can genuinely approximate live,
 * grounded in the device surveys (Notesprout `docs/onyx-pen-tools.md`: all 9 Onyx
 * firmware styles verified on five devices; the Ratta 0…31 pen-code sweep on Nomad +
 * Manta). Native codes below are the current best mapping — engine phases confirm them
 * on-device:
 *
 * | Style | Committed (all engines) | Onyx live (firmware style) | Ratta live (pen code) |
 * |---|---|---|---|
 * | [PEN] | uniform width | `STROKE_STYLE_PENCIL` (0) | `NEEDLE` (10) |
 * | [FOUNTAIN] | pressure/velocity-modulated width | `STROKE_STYLE_FOUNTAIN` (1) | `INK` (16) |
 * | [MARKER] | uniform, semi-transparent, flat caps | `STROKE_STYLE_MARKER` (2) | `NEEDLE` (10) |
 * | [PENCIL] | grain-textured | `STROKE_STYLE_CHARCOAL` (4) | `NEEDLE` (10) |
 * | [BRUSH] | broad, strongly pressure-modulated | `STROKE_STYLE_NEO_BRUSH` (3) | `INK` (16) |
 * | [CALLIGRAPHY] | chisel nib (direction-dependent width) | `STROKE_STYLE_SQUARE_PEN` (7) | code 15 (14 as fallback) |
 * | [DASH] | uniform width, dashed | `STROKE_STYLE_DASH` (5) | code 4 (dash stream) |
 * | [CROSS] | stream of small x marks | `STROKE_STYLE_CHARCOAL` (4) — no x-stream in firmware; nearest texture | code 3 (x stream) |
 *
 * The lasso gestures' trail chrome remains engine-internal (the engines arm their trail
 * styles themselves during selection), but both trail *appearances* are also offered
 * here as real pen types: [DASH] (native on both platforms) and [CROSS] (native on
 * Ratta; approximated live on Onyx, exact when baked).
 *
 * Rendering lands incrementally (decided Phase 1): the model and API carry the style from
 * day one so host data never needs a breaking migration, but engines may render richer
 * styles as [PEN] until their committed renderer is implemented. New enum values may be
 * added in future versions — hosts persisting the style should tolerate unknown values
 * (falling back to [PEN]).
 */
enum class StrokeStyle {
    /** Uniform-width line — the default, and the baseline every engine renders today. */
    PEN,

    /** Width modulated by pressure (and velocity where pressure is absent). */
    FOUNTAIN,

    /** Highlighter-like: uniform width, semi-transparent, squared ends. */
    MARKER,

    /** Graphite-like grain texture; pressure modulates darkness. */
    PENCIL,

    /** Broad brush: strongly pressure-modulated width, soft ends. */
    BRUSH,

    /** Chisel/square nib: width depends on stroke direction (calligraphy). */
    CALLIGRAPHY,

    /** Uniform-width dashed line. */
    DASH,

    /** Stream of small x marks along the path (Supernote's lasso-x appearance). */
    CROSS,
}
