package com.music.bitchord

import android.content.Intent
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeNotNull
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppIconSwitchingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertSingleLauncher(icon: AppIcon) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val launchers = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(1, launchers.size)
        assertEquals("com.music.bitchord.${icon.alias}", launchers.single().activityInfo.name)
        assertEquals(icon, AppIcon.current(context))
    }

    /** Run in one instrumentation process, then verify in a separate invocation. */
    @Test fun selectForRestartCheck() {
        val variant = InstrumentationRegistry.getArguments().getString("sonoraIcon")
        assumeNotNull(variant)
        val icon = AppIcon.valueOf(variant!!)
        AppIcon.apply(context, icon)
        assertSingleLauncher(icon)
        // Flush the preference so the separate-process check tests persisted state explicitly.
        assertTrue(context.getSharedPreferences("sonora_launcher_icon", 0).edit().putString("variant", icon.name).commit())
    }

    @Test fun verifyAfterRestart() {
        val variant = InstrumentationRegistry.getArguments().getString("sonoraIcon")
        assumeNotNull(variant)
        val icon = AppIcon.valueOf(variant!!)
        assertSingleLauncher(icon)
        assertEquals(icon.name, context.getSharedPreferences("sonora_launcher_icon", 0).getString("variant", null))
    }

    @Test fun allVariantsUseAdaptiveIconsAndSwitchWithoutRestartingTheProcess() {
        val original = AppIcon.current(context)
        val pid = Process.myPid()
        try {
            for (icon in AppIcon.entries) {
                val drawable = context.getDrawable(icon.iconRes)
                assertTrue("${icon.name} must use the system adaptive mask", drawable is AdaptiveIconDrawable)
                if (Build.VERSION.SDK_INT >= 33) assertNotNull((drawable as AdaptiveIconDrawable).monochrome)
                val bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
                drawable!!.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                val previews = File(context.getExternalFilesDir(null), "sonora-icons").apply { mkdirs() }
                File(previews, "${icon.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                AppIcon.apply(context, icon)
                assertSingleLauncher(icon)
                assertEquals(pid, Process.myPid())
                AppIcon.restore(context)
                assertSingleLauncher(icon)
            }
        } finally {
            AppIcon.apply(context, original)
        }
    }

    @Test fun obsoleteCollectionsMigrateToDefaultAndModernPreferencesRestore() {
        val original = AppIcon.current(context)
        val prefs = context.getSharedPreferences("sonora_launcher_icon", 0)
        try {
            AppIcon.apply(context, AppIcon.Dark)
            prefs.edit().putString("variant", "PixelNeonViolet").commit()
            AppIcon.restore(context)
            assertSingleLauncher(AppIcon.Default)
            prefs.edit().putString("variant", AppIcon.Light.name).commit()
            AppIcon.restore(context)
            assertSingleLauncher(AppIcon.Light)
        } finally {
            AppIcon.apply(context, original)
        }
    }
}
