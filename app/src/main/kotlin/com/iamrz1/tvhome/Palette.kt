package com.iamrz1.tvhome

import android.content.res.Configuration
import android.graphics.Color

enum class ThemeMode(val label: String) {
    AUTO("Auto (follow system)"),
    DARK("Dark"),
    LIGHT("Light"),
}

/** Palette holds the colors of the layers drawn over the wallpaper. The wallpaper itself never changes. */
class Palette private constructor(val dark: Boolean) {
    val pill = pick("#4D121A21", "#66F2F5F7")
    val dock = pick("#59121A21", "#73F2F5F7")
    val overlay = pick("#F0101820", "#F2EEF2F5")
    val tile = pick("#2A3640", "#CFD8DE")
    val label = pick("#FFFFFF", "#101820")
    val muted = pick("#9AA7B0", "#4A5A66")
    val focus = pick("#FFFFFF", "#0B1A24")
    val focusFill = pick("#22FFFFFF", "#22000000")

    private fun pick(forDark: String, forLight: String) = Color.parseColor(if (dark) forDark else forLight)

    companion object {
        fun resolve(mode: ThemeMode, config: Configuration): Palette {
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                // many TV builds report night as undefined, which should stay dark
                ThemeMode.AUTO -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_NO
            }
            return Palette(dark)
        }
    }
}
