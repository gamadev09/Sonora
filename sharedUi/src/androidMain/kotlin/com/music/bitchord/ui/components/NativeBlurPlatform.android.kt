package com.music.bitchord.ui.components

internal actual fun nativeBoxBlur(
    pixels: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    passes: Int,
): Boolean = NativeBlur.tryBoxBlur(pixels, width, height, radius, passes)
