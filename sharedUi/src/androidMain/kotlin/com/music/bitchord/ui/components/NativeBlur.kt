package com.music.bitchord.ui.components

/**
 * JNI bridge to the Rust `sonora_blur` native library (`native/sonora_blur`).
 * Falls back cleanly to the Kotlin implementation on ABIs without `libsonora_blur.so`.
 */
internal object NativeBlur {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("sonora_core")
        true
    }.getOrDefault(false)

    @JvmStatic
    private external fun boxBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        passes: Int,
    )

    fun tryBoxBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        passes: Int,
    ): Boolean {
        if (!loaded) return false
        boxBlur(pixels, width, height, radius, passes)
        return true
    }
}
