package com.music.bitchord

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Four adaptive Sonora icons. Only launcher aliases change; the activity and player stay running. */
enum class AppIcon(val alias: String, val iconRes: Int, val labelRes: Int) {
    Default("LauncherSonoraDefault", R.mipmap.ic_launcher, R.string.app_icon_default),
    Light("LauncherSonoraLight", R.mipmap.ic_sonora_light, R.string.light),
    Dark("LauncherSonoraDark", R.mipmap.ic_sonora_dark, R.string.dark),
    Monochrome("LauncherSonoraMonochrome", R.mipmap.ic_sonora_monochrome, R.string.app_icon_monochrome);

    private fun component(context: Context) = ComponentName(context.packageName, "com.music.bitchord.$alias")

    companion object {
        private const val STORE = "sonora_launcher_icon"
        private const val VARIANT = "variant"

        private fun enabled(context: Context, icon: AppIcon): Boolean =
            when (context.packageManager.getComponentEnabledSetting(icon.component(context))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == Default
                else -> false
            }

        fun current(context: Context): AppIcon = entries.firstOrNull { enabled(context, it) } ?: Default

        /** Old collections have no new preference and migrate to Default. New choices survive updates/restores. */
        @Synchronized
        fun restore(context: Context) {
            val saved = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(VARIANT, null)
            val selected = entries.firstOrNull { it.name == saved } ?: Default
            val active = entries.filter { enabled(context, it) }
            if (active != listOf(selected) || saved != selected.name) apply(context, selected)
        }

        @Synchronized
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            val changes = entries.filter { enabled(context, it) != (it == icon) }
            if (changes.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // One package-manager transaction: no intermediate duplicate or missing launcher entry.
                    pm.setComponentEnabledSettings(changes.map {
                        PackageManager.ComponentEnabledSetting(
                            it.component(context),
                            if (it == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                    })
                } else {
                    // API 26–32 has no batch operation. Enable first so the app is always reachable.
                    if (icon in changes) pm.setComponentEnabledSetting(icon.component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    changes.filter { it != icon }.forEach {
                        pm.setComponentEnabledSetting(it.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    }
                }
            }
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(VARIANT, icon.name).apply()
        }
    }
}

/** Normalises restored choices after an update, even before the first manual launch. */
class AppIconUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AppIcon.restore(context)
    }
}
