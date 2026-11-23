package com.iamrz1.tvhome

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Wallpaper is a gradient through [hex] colors along [angle] degrees, where 90 runs from the top to the bottom. */
class Wallpaper(val name: String, val light: Boolean, private val angle: Int, vararg hex: String) {
    private val colors = IntArray(hex.size) { Color.parseColor(hex[it]) }
    private val stops = FloatArray(hex.size) { it / (hex.size - 1f) }

    fun drawable(): Drawable = ShapeDrawable(RectShape()).apply {
        shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader {
                val rad = Math.toRadians(angle.toDouble())
                val dx = cos(rad).toFloat()
                val dy = sin(rad).toFloat()
                // the line runs through the center and is long enough to reach both corners
                val half = (abs(dx) * width + abs(dy) * height) / 2
                val cx = width / 2f
                val cy = height / 2f
                return LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, stops, Shader.TileMode.CLAMP)
            }
        }
    }
}

/** Wallpapers keeps new entries at the end, because the saved choice is an index. */
object Wallpapers {
    val all = listOf(
        Wallpaper("Dusk", false, 90, "#16232C", "#3A6076", "#16232C"),
        Wallpaper("Ember", false, 90, "#2A1512", "#7A3B2A", "#2A1512"),
        Wallpaper("Forest", false, 90, "#10201A", "#2F6B52", "#10201A"),
        Wallpaper("Plum", false, 90, "#1E1426", "#5B3A7A", "#1E1426"),
        Wallpaper("Graphite", false, 90, "#0E0F11", "#2B2F36", "#0E0F11"),
        Wallpaper("Ocean", false, 90, "#0B1D2E", "#1F5F8B", "#0B1D2E"),
        Wallpaper("Sunset", false, 90, "#2B1530", "#B5533C", "#2B1530"),
        Wallpaper("Aurora", false, 90, "#0C1F24", "#2E8B7A", "#0C1F24"),
        Wallpaper("Midnight", false, 90, "#0A0F24", "#2C3E8C", "#0A0F24"),
        Wallpaper("Rose", false, 90, "#2A1620", "#9A4A66", "#2A1620"),
        Wallpaper("Slate", false, 90, "#14181D", "#4B5663", "#14181D"),
        Wallpaper("Moss", false, 90, "#14190F", "#5C7A3A", "#14190F"),
        Wallpaper("Amber", false, 90, "#241A0C", "#A8741F", "#241A0C"),
        Wallpaper("Crimson", false, 90, "#1F0C10", "#8A2434", "#1F0C10"),
        Wallpaper("Sky", true, 65, "#A8D8F5", "#FFB07C", "#E8A3E6"),
        Wallpaper("Cloud", true, 65, "#EEF1F4", "#C9D3DA", "#A9B8C0"),
        Wallpaper("Haze", true, 65, "#CFE0F5", "#B3C6E4", "#9CB3D3"),
        Wallpaper("Lagoon", true, 65, "#2DB5C4", "#9FDCCB", "#F1E3B8"),
        Wallpaper("Candy", true, 65, "#FFD3DE", "#FFF6BD", "#B7E8FF"),
        Wallpaper("Dawn", true, 65, "#8F7BD6", "#E3A1C4", "#F9D2B8"),
        Wallpaper("Mint", true, 65, "#C8F0DC", "#E8F7C8", "#BFE3F5"),
    )

    fun get(index: Int): Wallpaper = all.getOrElse(index) { all.first() }
}
