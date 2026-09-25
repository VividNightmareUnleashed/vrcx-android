package io.github.vrcxandroid.host

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat

/**
 * System bars and window background (docs/ARCHITECTURE.md §6.7). The page draws
 * edge-to-edge; bars are transparent and only their icon colour follows `AppApi.ChangeTheme`. The last theme is stored
 * so the next cold start paints the right frame colour before the page loads.
 */
object HostTheme {
    private const val KEY_MODE = "theme_mode"
    private const val KEY_SYSTEM_NIGHT = "theme_system_night"

    @Volatile
    var current: ThemeColors = ThemeColors.forMode(ThemeColors.LIGHT)!!
        private set

    fun isSystemNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Called in MainActivity.onCreate before the content view is set. */
    fun applyStartup(activity: Activity) {
        current = ThemeColors.forMode(expectedMode(activity))!!
        configureWindow(activity)
        applyToWindow(activity, current)
        WebViewHolder.backgroundColor = current.frame
    }

    /**
     * The system switched between light and dark (MainActivity handles uiMode itself). When the page follows the system
     * theme it calls ChangeTheme a moment later; repaint the frame and bar icons now so they do not lag behind.
     */
    fun onSystemNightChanged(activity: Activity) {
        val colors = ThemeColors.forMode(expectedMode(activity))!!
        if (colors == current) return
        current = colors
        applyToWindow(activity, colors)
        WebViewHolder.backgroundColor = colors.frame
    }

    private fun expectedMode(context: Context): Int {
        val prefs = VrcxHost.prefs
        val storedMode = if (prefs.contains(KEY_MODE)) prefs.getInt(KEY_MODE, 0) else null
        val storedNight = if (prefs.contains(KEY_SYSTEM_NIGHT)) prefs.getBoolean(KEY_SYSTEM_NIGHT, false) else null
        return ThemeColors.startupMode(storedMode, storedNight, isSystemNight(context))
    }

    /** AppApi.ChangeTheme(0 light, 1 dark, 2 midnight). Any thread. */
    fun apply(mode: Int) {
        val colors = ThemeColors.forMode(mode) ?: return
        current = colors
        VrcxHost.prefs.edit()
            .putInt(KEY_MODE, mode)
            .putBoolean(KEY_SYSTEM_NIGHT, isSystemNight(VrcxHost.app))
            .apply()
        VrcxHost.main.post {
            VrcxHost.activity?.let { applyToWindow(it, colors) }
            WebViewHolder.backgroundColor = colors.frame
        }
    }

    @Suppress("DEPRECATION")
    private fun configureWindow(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
            window.decorView.isForceDarkAllowed = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    fun applyToWindow(activity: Activity, colors: ThemeColors) {
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(colors.frame))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = colors.light
            isAppearanceLightNavigationBars = colors.light
        }
    }
}
