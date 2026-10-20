package dev.xvin.palimpsest.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.xvin.palimpsest.R

/**
 * The launcher icons to choose from. Each is an `activity-alias` of MainActivity in the manifest;
 * exactly one is enabled. Switching takes a few seconds to show on the home screen, and some
 * launchers drop the old shortcut from the home screen (the app stays in the app drawer).
 */
enum class AppIcon(val alias: String, @StringRes val labelRes: Int, @DrawableRes val background: Int, @DrawableRes val foreground: Int) {
    CLASSIC("IconClassic", R.string.icon_classic, R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    VINYL("IconVinyl", R.string.icon_vinyl, R.drawable.ic_icon_vinyl_bg, R.drawable.ic_icon_vinyl_fg),
    LAYERS("IconLayers", R.string.icon_layers, R.drawable.ic_icon_layers_bg, R.drawable.ic_icon_layers_fg),
    NEON("IconNeon", R.string.icon_neon, R.drawable.ic_icon_neon_bg, R.drawable.ic_icon_neon_fg),
    CASSETTE("IconCassette", R.string.icon_cassette, R.drawable.ic_icon_cassette_bg, R.drawable.ic_icon_cassette_fg),
    LANE("IconLane", R.string.icon_lane, R.drawable.ic_icon_lane_bg, R.drawable.ic_icon_lane_fg);

    private fun component(context: Context) = ComponentName(context.packageName, "dev.xvin.palimpsest.$alias")

    companion object {
        /** The icon the launcher shows now. */
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull { icon ->
                when (pm.getComponentEnabledSetting(icon.component(context))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == CLASSIC // as declared in the manifest
                    else -> false
                }
            } ?: CLASSIC
        }

        /** Enables [icon]'s alias first (so there's never no launcher entry), then disables the rest. */
        fun set(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(icon.component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(it.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
