package com.music.bitchord.ui.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.ui.tv.components.TvDialog
import com.music.bitchord.ui.tv.components.TvDialogButton
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvType

/**
 * Hardware capability profile for Android TV devices.
 *
 * On low-RAM or 32-bit-only TVs (e.g. 1 GB MT5867 `armeabi-v7a` TVs), heavy
 * on-device DSP features like Sing (vocal separation) and Automix can cause
 * slow loading or audio stutter, so Sonora asks the user before enabling them.
 */
data class TvHardwareProfile(
    val totalRamMb: Int,
    val isLowRamDevice: Boolean,
    val is32BitOnly: Boolean,
) {
    val isWeakTv: Boolean
        get() = totalRamMb < 2048 || isLowRamDevice || is32BitOnly

    val summary: String
        get() {
            val arch = if (is32BitOnly) "32-bit ARM (${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()})" else "64-bit ARM"
            return "Detected: $totalRamMb MB RAM · $arch"
        }
}

object TvDeviceCapabilities {
    @Volatile
    private var cached: TvHardwareProfile? = null

    fun profile(context: Context): TvHardwareProfile {
        cached?.let { return it }
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val totalRamMb = (info.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(0)
        val isLowRam = am?.isLowRamDevice == true
        val is32BitOnly = Build.SUPPORTED_64_BIT_ABIS.isEmpty()
        return TvHardwareProfile(
            totalRamMb = totalRamMb,
            isLowRamDevice = isLowRam,
            is32BitOnly = is32BitOnly,
        ).also { cached = it }
    }
}

/**
 * Confirmation dialog shown when a user enables a heavy DSP feature (Sing or
 * Automix) on a low-memory or 32-bit Android TV.
 */
@Composable
fun TvWeakHardwareDialog(
    featureName: String,
    profile: TvHardwareProfile,
    onKeepOff: () -> Unit,
    onEnableAnyway: () -> Unit,
) {
    TvDialog(
        title = "This TV May Struggle with $featureName",
        message = "Real-time vocal separation and Automix require significant RAM and CPU. Enabling $featureName on lower-memory TVs may cause slow loading or audio stutter.",
        onDismissRequest = onKeepOff,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0x2EF59E0B)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFFBBF24),
                    modifier = Modifier.size(30.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B0E17))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.summary,
                    style = TvType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    color = TvGlass.TextPrimary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TvDialogButton(
                text = "Keep Off (Recommended)",
                initialFocus = true,
                onClick = onKeepOff,
            )
            TvDialogButton(
                text = "Enable Anyway",
                onClick = onEnableAnyway,
            )
        }
    }
}
