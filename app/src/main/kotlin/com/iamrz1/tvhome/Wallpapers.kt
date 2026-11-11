package com.iamrz1.tvhome

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape

class Wallpaper(val name: String, edge: String, mid: String) {
    private val colors = intArrayOf(Color.parseColor(edge), Color.parseColor(mid), Color.parseColor(edge))

    fun drawable(): Drawable = ShapeDrawable(RectShape()).apply {
        shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader =
                LinearGradient(0f, 0f, 0f, height.toFloat(), colors, STOPS, Shader.TileMode.CLAMP)
        }
    }

    private companion object {
        val STOPS = floatArrayOf(0f, 0.5f, 1f)
    }
}

object Wallpapers {
    val all = listOf(
        Wallpaper("Dusk", "#16232C", "#3A6076"),
        Wallpaper("Ember", "#2A1512", "#7A3B2A"),
        Wallpaper("Forest", "#10201A", "#2F6B52"),
        Wallpaper("Plum", "#1E1426", "#5B3A7A"),
        Wallpaper("Graphite", "#0E0F11", "#2B2F36"),
    )

    fun get(index: Int): Wallpaper = all.getOrElse(index) { all.first() }
}
