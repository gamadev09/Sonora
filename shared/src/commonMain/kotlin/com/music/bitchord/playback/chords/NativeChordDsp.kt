package com.music.bitchord.playback.chords

/**
 * Optional native Rust FFT + chroma acceleration (`sonora-core` via `libsonora_core.so`).
 * Returns `true` if `outChroma` (length >= 12) was computed natively; `false` triggers
 * the portable Kotlin fallback with identical mathematical behavior.
 */
internal expect fun nativeComputeChroma(
    ring: DoubleArray,
    base: Int,
    outChroma: DoubleArray,
): Boolean
