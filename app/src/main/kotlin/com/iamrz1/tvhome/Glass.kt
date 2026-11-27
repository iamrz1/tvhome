package com.iamrz1.tvhome

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.min

/**
 * GlassBlur keeps a small, blurred copy of the wallpaper. Android 9 to 11 cannot blur what is behind a view, but the
 * wallpaper is a fixed gradient, so a glass layer can draw the part of this copy that lies under it instead.
 */
object GlassBlur {
    var bitmap: Bitmap? = null
        private set

    // the size of the screen that the copy stands for
    private var width = 0
    private var height = 0

    /** True when the copy was made for a screen of this size, so there is nothing to redo. */
    fun matches(w: Int, h: Int) = bitmap != null && w == width && h == height

    fun update(wallpaper: Drawable, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val bw = maxOf(w / SCALE, 1)
        val bh = maxOf(h / SCALE, 1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val old = wallpaper.copyBounds()
        wallpaper.setBounds(0, 0, bw, bh)
        wallpaper.draw(Canvas(bmp))
        wallpaper.bounds = old
        val pixels = IntArray(bw * bh)
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        repeat(PASSES) {
            blur(pixels, bw, bh, true)
            blur(pixels, bw, bh, false)
        }
        bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        bitmap = bmp
        width = w
        height = h
    }

    fun scaleX() = width.toFloat() / (bitmap?.width ?: 1)

    fun scaleY() = height.toFloat() / (bitmap?.height ?: 1)

    // a running average along rows or columns
    private fun blur(px: IntArray, w: Int, h: Int, rows: Boolean) {
        val lines = if (rows) h else w
        val len = if (rows) w else h
        val line = IntArray(len)
        for (l in 0 until lines) {
            for (i in 0 until len) line[i] = px[if (rows) l * w + i else i * w + l]
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (i in 0 until minOf(RADIUS, len)) {
                val c = line[i]
                a += Color.alpha(c); r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
            for (i in 0 until len) {
                val add = i + RADIUS
                if (add < len) {
                    val c = line[add]
                    a += Color.alpha(c); r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                }
                val drop = i - RADIUS - 1
                if (drop >= 0) {
                    val c = line[drop]
                    a -= Color.alpha(c); r -= Color.red(c); g -= Color.green(c); b -= Color.blue(c); n--
                }
                px[if (rows) l * w + i else i * w + l] = Color.argb(a / n, r / n, g / n, b / n)
            }
        }
    }

    private const val SCALE = 8
    private const val RADIUS = 5
    private const val PASSES = 3
}

/**
 * GlassDrawable draws a layer of frosted glass over its [anchor] view: the blurred wallpaper under it, a tint, a soft
 * light from the top, a thin edge and a brighter edge along the top. [radius] is in pixels, and a negative value
 * means a full circle or capsule. With [dim] it is only the blurred wallpaper and the tint.
 */
class GlassDrawable(
    private val anchor: View,
    private val radius: () -> Float,
    private val tint: Int,
    private val topLight: Int = 0x4D,
    private val bottomLight: Int = 0x1A,
    private val dim: Boolean = false,
    // Draw the glass into a bitmap once and show that. A view that sits in a hardware layer already does this itself.
    private val bake: Boolean = true,
) : Drawable() {
    private val density = anchor.resources.displayMetrics.density
    // glass over colour makes the colour stronger, as the blurred glass of the design does
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(if (dim) 1.5f else 1.9f) })
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val light = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val top = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private var lightShader: Shader? = null
    private var topShader: Shader? = null
    private var shaderHeight = -1f
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null
    private val where = IntArray(2)

    private var baked: Bitmap? = null
    private var bakedKey = ""

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        anchor.getLocationInWindow(where)
        if (!bake) {
            paint(canvas, b)
            return
        }
        // Five layers of shaders over a wide area are slow on a weak GPU, and they were drawn again for every frame of
        // a tile that moves on top of them. The result only changes with the size, the place, the wallpaper and the corner.
        val key = "${b.left},${b.top},${b.width()},${b.height()},${where[0]},${where[1]},${System.identityHashCode(GlassBlur.bitmap)},${radius()}"
        var bmp = baked
        if (bmp == null || key != bakedKey) {
            bmp = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(-b.left.toFloat(), -b.top.toFloat())
            paint(c, b)
            baked = bmp
            bakedKey = key
        }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), smooth)
    }

    private fun paint(canvas: Canvas, b: android.graphics.Rect) {
        val rect = RectF(b)
        val r = radius().let { if (it < 0f) min(rect.width(), rect.height()) / 2f else it }
        GlassBlur.bitmap?.let { bmp ->
            if (shaderBitmap !== bmp) {
                shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderBitmap = bmp
            }
            // the copy stands for the whole window, so it is moved back by where this view is in the window
            matrix.setScale(GlassBlur.scaleX(), GlassBlur.scaleY())
            matrix.postTranslate(-where[0].toFloat(), -where[1].toFloat())
            shader?.setLocalMatrix(matrix)
            blurPaint.shader = shader
            canvas.drawRoundRect(rect, r, r, blurPaint)
        }
        fill.color = tint
        canvas.drawRoundRect(rect, r, r, fill)
        if (dim) return
        // the gradients only change with the size, so they are not made again for every frame
        if (shaderHeight != rect.height() + rect.top) {
            lightShader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                Color.argb(topLight, 255, 255, 255), Color.argb(bottomLight, 255, 255, 255), Shader.TileMode.CLAMP,
            )
            topShader = LinearGradient(
                0f, rect.top, 0f, rect.top + rect.height() * 0.4f,
                Color.argb(0xBB, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP,
            )
            shaderHeight = rect.height() + rect.top
        }
        light.shader = lightShader
        canvas.drawRoundRect(rect, r, r, light)
        val thin = density
        edge.strokeWidth = thin
        edge.color = Color.argb(0x47, 255, 255, 255)
        val inset = thin / 2
        val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        canvas.drawRoundRect(inner, r - inset, r - inset, edge)
        top.strokeWidth = thin * 1.5f
        top.shader = topShader
        canvas.drawRoundRect(inner, r - inset, r - inset, top)
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        val r = radius().let { if (it < 0f) min(b.width(), b.height()) / 2f else it }
        outline.setRoundRect(b, r)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
