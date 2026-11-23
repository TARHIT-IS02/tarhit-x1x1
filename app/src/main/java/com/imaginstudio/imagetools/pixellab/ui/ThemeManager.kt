package com.imaginstudio.imagetools.pixellab.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.SeekBar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

object ThemeManager {

    private const val PREFS = "tz_photo_edit_prefs"
    private const val KEY_DARK_THEME = "dark_theme"

    @Volatile
    private var cachedDark: Boolean? = null

    fun isDark(context: Context): Boolean {
        cachedDark?.let { return it }
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_THEME, false)
        cachedDark = v
        return v
    }

    fun setDark(context: Context, dark: Boolean) {
        cachedDark = dark
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_THEME, dark)
            .apply()
    }

    fun toggleDark(context: Context): Boolean {
        val next = !isDark(context)
        setDark(context, next)
        return next
    }

    // HTML [data-theme="dark"] exact tokens vs PixelLab Light tokens
    fun workspaceBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#121212") else Color.parseColor("#E3E3E3")

    fun headerBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#1E1E1E") else Color.parseColor("#1577E6")

    fun zoomPillBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#2A2A2E") else Color.parseColor("#33000000")

    fun panelBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#1E1E1E") else Color.parseColor("#FAFAFA")

    fun sheetBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#1E1E1E") else Color.parseColor("#F5F5F5")

    fun panelSecondaryBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#242426") else Color.parseColor("#F1F5F9")

    fun tabsBarBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#18181B") else Color.parseColor("#E6E6E6")

    fun cardBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#1E1E1E") else Color.WHITE

    fun cardElevatedBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#262629") else Color.parseColor("#F8FAFC")

    fun textPrimary(dark: Boolean): Int =
        if (dark) Color.parseColor("#F2F2F4") else Color.parseColor("#1E293B")

    fun textSecondary(dark: Boolean): Int =
        if (dark) Color.parseColor("#9A9AA2") else Color.parseColor("#64748B")

    fun iconInactive(dark: Boolean): Int =
        if (dark) Color.parseColor("#B0B0B8") else Color.parseColor("#777777")

    fun iconActive(dark: Boolean): Int =
        if (dark) Color.parseColor("#FFFFFF") else Color.parseColor("#444B54")

    fun selectedStripBubble(dark: Boolean): Int =
        if (dark) Color.parseColor("#32323A") else Color.parseColor("#D8DCE2")

    fun tabActiveColor(dark: Boolean): Int =
        if (dark) Color.parseColor("#4E8CFF") else Color.parseColor("#418DC3")

    fun tabInactiveColor(dark: Boolean): Int =
        if (dark) Color.parseColor("#767680") else Color.parseColor("#BFBFBF")

    fun accentPrimary(dark: Boolean): Int =
        if (dark) Color.parseColor("#2F6BFF") else Color.parseColor("#009688")

    fun accentBlue(dark: Boolean): Int =
        if (dark) Color.parseColor("#4E8CFF") else Color.parseColor("#2693B5")

    fun accentSoftBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#2E2F6BFF") else Color.parseColor("#E0F2FE")

    fun dividerColor(dark: Boolean): Int =
        if (dark) Color.parseColor("#2C2C32") else Color.parseColor("#D8D8D8")

    fun borderColor(dark: Boolean): Int =
        if (dark) Color.parseColor("#36363E") else Color.parseColor("#CBD5E1")

    fun seekTrackColor(dark: Boolean): Int =
        if (dark) Color.parseColor("#323236") else Color.parseColor("#E1E4EA")

    fun dangerColor(dark: Boolean): Int =
        if (dark) Color.parseColor("#FF4D4F") else Color.parseColor("#DC2626")

    fun actionColBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#26262C") else Color.parseColor("#8FB0AF")

    fun actionColDivider(dark: Boolean): Int =
        if (dark) Color.parseColor("#383842") else Color.parseColor("#789A99")

    fun floatingBarBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#EB1E1E22") else Color.parseColor("#F5FFFFFF")

    fun statusBarBg(dark: Boolean): Int =
        if (dark) Color.parseColor("#141416") else Color.BLACK

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).roundToInt()

    fun applySeekBarTheme(sb: SeekBar, dark: Boolean) {
        val accent = accentPrimary(dark)
        sb.thumbTintList = ColorStateList.valueOf(accent)
        sb.progressTintList = ColorStateList.valueOf(accent)
        sb.progressBackgroundTintList = ColorStateList.valueOf(seekTrackColor(dark))
    }

    fun applySwitchTheme(sw: SwitchMaterial, dark: Boolean) {
        val accent = accentPrimary(dark)
        sw.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, if (dark) Color.parseColor("#D4D4D8") else Color.WHITE)
        )
        sw.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, if (dark) Color.parseColor("#3F3F46") else Color.parseColor("#B0BEC5"))
        )
    }

    fun dialogCardDrawable(context: Context, dark: Boolean, radiusDp: Int = 12): GradientDrawable {
        return GradientDrawable().apply {
            setColor(cardBg(dark))
            cornerRadius = dp(context, radiusDp).toFloat()
            if (dark) {
                setStroke(dp(context, 1), dividerColor(true))
            }
        }
    }

    fun inputDrawable(context: Context, dark: Boolean, radiusDp: Int = 8): GradientDrawable {
        return GradientDrawable().apply {
            setColor(panelSecondaryBg(dark))
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1), borderColor(dark))
        }
    }

    fun pillDrawable(context: Context, dark: Boolean, active: Boolean, radiusDp: Int = 999): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (active) accentSoftBg(dark) else cardElevatedBg(dark))
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1), if (active) accentPrimary(dark) else borderColor(dark))
        }
    }

    fun floatingBarDrawable(context: Context, dark: Boolean, radiusDp: Int = 14): GradientDrawable {
        return GradientDrawable().apply {
            setColor(floatingBarBg(dark))
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1), borderColor(dark))
        }
    }

    fun circleButtonDrawable(context: Context, dark: Boolean, active: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) accentSoftBg(dark) else cardElevatedBg(dark))
            setStroke(dp(context, 1), if (active) accentPrimary(dark) else borderColor(dark))
        }
    }
}
