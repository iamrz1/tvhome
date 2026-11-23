package com.iamrz1.tvhome

import android.content.res.Configuration
import android.graphics.Color

enum class ThemeMode(val label: String) {
    AUTO("Auto (follow system)"),
    DARK("Dark"),
    LIGHT("Light"),
}

/** Palette holds the colors of the layers drawn over the wallpaper. The wallpaper itself never changes. */
class Palette private constructor(val dark: Boolean, dockPercent: Int) {
    val pill = pick("#4D121A21", "#66F2F5F7")
    val dock = pick("#121A21", "#F2F5F7") and 0x00FFFFFF or (dockPercent * 255 / 100 shl 24)
    val overlay = pick("#40121A21", "#4DF2F5F7")
    val tile = pick("#2A3640", "#CFD8DE")
    val label = pick("#FFFFFF", "#101820")
    val muted = pick("#9AA7B0", "#4A5A66")
    val focus = pick("#FFFFFF", "#0B1A24")
    val focusFill = pick("#22FFFFFF", "#22000000")

    // the full screen settings
    val settingsBg = pick("#2B2B2B", "#F1F1F3")
    val option = pick("#383838", "#DFDFE3")
    val optionFocus = pick("#FFFFFF", "#202020")
    val optionText = pick("#FFFFFF", "#202020")
    val optionTextFocus = pick("#1B1B1B", "#FFFFFF")
    val settingsMuted = pick("#9A9A9A", "#6B6B70")

    private fun pick(forDark: String, forLight: String) = Color.parseColor(if (dark) forDark else forLight)

    companion object {
        fun resolve(mode: ThemeMode, config: Configuration, dockPercent: Int): Palette {
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                // many TV builds report night as undefined, which should stay dark
                ThemeMode.AUTO -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_NO
            }
            return Palette(dark, dockPercent)
        }
    }
}
