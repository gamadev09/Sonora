package com.music.bitchord.ui.components

/** Platform hook for native Rust box blur (`libsonora_blur.so` on Android). */
internal expect fun nativeBoxBlur(
    pixels: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    passes: Int,
): Boolean
