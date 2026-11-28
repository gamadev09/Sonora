package com.music.bitchord.ui.tv.auth

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TvQrCodeView(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 152.dp,
    quietZone: Int = 4,
) {
    val qrBitmap by produceState<ImageBitmap?>(initialValue = null, content, quietZone) {
        value = withContext(Dispatchers.Default) {
            runCatching { renderQrBitmap(content, quietZone) }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(13.dp))
            .background(Color.White)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "QR code",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun renderQrBitmap(content: String, quietZone: Int): ImageBitmap? {
    if (content.isBlank()) return null
    val matrix = QrCodeGenerator.encode(content)
    val height = matrix.size
    if (height == 0) return null
    val width = matrix[0].size
    val totalW = width + quietZone * 2
    val totalH = height + quietZone * 2
    val dark = 0xFF08080B.toInt()
    val light = 0xFFFFFFFF.toInt()
    val pixels = IntArray(totalW * totalH) { light }
    for (y in 0 until height) {
        val row = matrix[y]
        val destRowOffset = (y + quietZone) * totalW + quietZone
        for (x in 0 until width) {
            if (row[x]) pixels[destRowOffset + x] = dark
        }
    }
    return Bitmap.createBitmap(pixels, totalW, totalH, Bitmap.Config.ARGB_8888).asImageBitmap()
}
