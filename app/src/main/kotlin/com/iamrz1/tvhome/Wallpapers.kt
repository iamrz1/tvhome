package com.iamrz1.tvhome

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Wallpaper is a gradient through [hex] colors along [angle] degrees, where 90 runs from the top to the bottom. */
class Wallpaper(val name: String, val light: Boolean, private val angle: Int, vararg hex: String) {
    private val colors = IntArray(hex.size) { Color.parseColor(hex[it]) }
    private val stops = FloatArray(hex.size) { it / (hex.size - 1f) }
    private val glows = ArrayList<Glow>()

    /**
     * A spot of light: its center as a part of the width and the height, and how far it reaches as a part of the width
     * and of the height, so it can be an ellipse. [orb] is a ball with a lit side instead: [color] is its lit side, [dark]
     * the other, and [reach] its diameter as a part of the width.
     */
    class Glow(val x: Float, val y: Float, val reach: Float, val reachY: Float, val color: Int, val dark: Int = 0, val orb: Boolean = false)

    /** Adds a spot of light over the gradient, which gives glass something to show. [hex] may carry an alpha. */
    fun glow(x: Float, y: Float, reach: Float, hex: String, reachY: Float = reach * 16f / 9f) =
        apply { glows += Glow(x, y, reach, reachY, Color.parseColor(hex)) }

    /** Adds a glossy ball, which gives the blur of the glass a shape to show. */
    fun orb(x: Float, y: Float, diameter: Float, lit: String, dark: String) =
        apply { glows += Glow(x, y, diameter, diameter, Color.parseColor(lit), Color.parseColor(dark), orb = true) }

    fun drawable() = Gradient(colors, stops, angle, glows.toList())

    /** Gradient draws the colors along its angle, and the spots of light over them. */
    class Gradient(
        private val colors: IntArray,
        private val stops: FloatArray,
        private val angle: Int,
        private val glows: List<Glow>,
    ) : Drawable() {
        private val paint = Paint()
        private val glowPaints = glows.map { Paint(Paint.ANTI_ALIAS_FLAG) }
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (paint.shader == null) {
                paint.shader = shader(b.width(), b.height())
                glows.forEachIndexed { i, g ->
                    if (g.orb) {
                        val d = g.reach * w
                        glowPaints[i].shader = RadialGradient(
                            g.x * w - d * 0.15f, g.y * h - d * 0.20f, d * 0.955f,
                            intArrayOf(g.color, g.dark, g.dark), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
                        )
                    } else {
                        // a round gradient of radius one, stretched to the ellipse
                        val shader = RadialGradient(0f, 0f, 1f, g.color, g.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
                        matrix.setScale(g.reach * w, g.reachY * h)
                        matrix.postTranslate(g.x * w, g.y * h)
                        shader.setLocalMatrix(matrix)
                        glowPaints[i].shader = shader
                    }
                }
            }
            canvas.drawRect(b, paint)
            glows.forEachIndexed { i, g ->
                if (g.orb) canvas.drawCircle(g.x * w, g.y * h, g.reach * w / 2f, glowPaints[i]) else canvas.drawRect(b, glowPaints[i])
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            paint.shader = null
        }

        private fun shader(width: Int, height: Int): Shader {
            val rad = Math.toRadians(angle.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            // the line runs through the center and is long enough to reach both corners
            val half = (abs(dx) * width + abs(dy) * height) / 2
            val cx = width / 2f
            val cy = height / 2f
            return LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, stops, Shader.TileMode.CLAMP)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(filter: ColorFilter?) {
            paint.colorFilter = filter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
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
        // vivid, with spots of light, for the glass style
        // the wallpaper of the glass design
        Wallpaper("Prism", false, 45, "#4B2FC9", "#A53BB5", "#FF7F66")
            .glow(0.10f, 0.08f, 0.3224f, "#D93DD6F5", 0.3844f)
            .glow(0.92f, 0.12f, 0.3072f, "#D9FF70A8", 0.3840f)
            .glow(0.90f, 0.98f, 0.3472f, "#F2FFCF64", 0.4092f)
            .orb(0.1492f, 0.5486f, 0.1484f, "#7DF3FF", "#1FA8E8")
            .orb(0.8984f, 0.6528f, 0.1719f, "#FFE08A", "#FF8A3D"),
        Wallpaper("Lumen", false, 30, "#0F2A6B", "#1E6FD9", "#3CE0D0")
            .glow(0.92f, 0.10f, 0.50f, "#CCFF5FA8")
            .glow(0.12f, 0.95f, 0.55f, "#CCFFC857"),
        Wallpaper("Nebula", false, 40, "#1B1048", "#6B2FB8", "#E04F9A")
            .glow(0.85f, 0.90f, 0.50f, "#CC3DE0F5")
            .glow(0.10f, 0.10f, 0.50f, "#B3FF9A4D"),
        Wallpaper("Coral", false, 120, "#FF6F61", "#FF3D81", "#7A3DFF")
            .glow(0.10f, 0.90f, 0.55f, "#E6FFD36B")
            .glow(0.90f, 0.10f, 0.50f, "#B34DE1FF"),
        Wallpaper("Opal", true, 65, "#BFD9FF", "#E6C8FF", "#FFD3E0")
            .glow(0.10f, 0.10f, 0.50f, "#CC8EF0E0")
            .glow(0.90f, 0.90f, 0.55f, "#CCFFC8A0"),
    )

    /** What a fresh install starts with. */
    val DEFAULT get() = all.indexOfFirst { it.name == "Sky" }

    fun get(index: Int): Wallpaper = all.getOrElse(index) { all.first() }
}
