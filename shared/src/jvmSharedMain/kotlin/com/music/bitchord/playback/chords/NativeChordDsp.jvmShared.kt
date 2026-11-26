package com.music.bitchord.playback.chords

internal object NativeChordDspAndroid {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("sonora_core")
        true
    }.getOrDefault(false)

    @JvmStatic
    private external fun computeChroma(
        ring: DoubleArray,
        base: Int,
        outChroma: DoubleArray,
    ): Boolean

    fun tryComputeChroma(ring: DoubleArray, base: Int, outChroma: DoubleArray): Boolean {
        if (!loaded) return false
        return computeChroma(ring, base, outChroma)
    }
}

internal actual fun nativeComputeChroma(
    ring: DoubleArray,
    base: Int,
    outChroma: DoubleArray,
): Boolean = NativeChordDspAndroid.tryComputeChroma(ring, base, outChroma)
