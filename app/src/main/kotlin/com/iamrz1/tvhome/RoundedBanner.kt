package com.iamrz1.tvhome

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * RoundedBanner draws [banner] scaled to fill its bounds, cropped to a rounded rect of [radius] pixels. The banner is
 * drawn once into a bitmap and painted with a shader, so the corners are smooth and do not rely on view clipping,
 * which some devices skip for an image that fills its parent.
 *
 * Some banners have rounded corners of their own, which show the tile behind them when the tile is square. With
 * [fillCorners] the transparent corners of such a banner are filled with the colors next to them, so only the shape
 * of the tile shows.
 */
class RoundedBanner(
    private val banner: Drawable,
    private val radius: Float,
    private val fillCorners: Boolean = false,
) : Drawable() {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        bitmap = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = bitmap ?: render(b.width(), b.height()).also { bitmap = it }
        paint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRoundRect(RectF(0f, 0f, b.width().toFloat(), b.height().toFloat()), radius, radius, paint)
        canvas.restore()
    }

    // center crop: the banner covers the bounds, and the overflow is cut off
    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val iw = banner.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = banner.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = maxOf(w.toFloat() / iw, h.toFloat() / ih)
        val canvas = Canvas(bmp)
        canvas.translate((w - iw * scale) / 2f, (h - ih * scale) / 2f)
        canvas.scale(scale, scale)
        banner.setBounds(0, 0, iw, ih)
        banner.draw(canvas)
        if (fillCorners) fillTransparentCorners(bmp)
        return bmp
    }

    // A banner with its own rounded corners is opaque along its edges and transparent in its corners. Each row of a
    // corner is filled with the first opaque color of that row, so a gradient continues into the corner. A banner with
    // a transparent background has transparent edges too, and is left as it is.
    private fun fillTransparentCorners(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        fun opaque(x: Int, y: Int) = Color.alpha(bmp.getPixel(x, y)) >= OPAQUE
        val edges = listOf(w / 2 to 1, w / 2 to h - 2, 1 to h / 2, w - 2 to h / 2)
        if (!edges.all { (x, y) -> opaque(x, y) }) return
        val reach = (minOf(w, h) * CORNER_REACH).toInt()
        for (y in 0 until h) {
            if (y >= reach && y < h - reach) continue
            val left = (0 until w / 2).firstOrNull { opaque(it, y) } ?: continue
            if (left in 1..reach) {
                val color = bmp.getPixel(left, y)
                for (x in 0 until left) bmp.setPixel(x, y, color)
            }
            val right = (w - 1 downTo w / 2).firstOrNull { opaque(it, y) } ?: continue
            if (w - 1 - right in 1..reach) {
                val color = bmp.getPixel(right, y)
                for (x in right + 1 until w) bmp.setPixel(x, y, color)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val OPAQUE = 250

        // how far a corner reaches along an edge, as a part of the shorter side
        const val CORNER_REACH = 0.45f
    }
}
