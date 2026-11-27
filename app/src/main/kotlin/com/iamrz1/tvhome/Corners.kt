package com.iamrz1.tvhome

/**
 * Corners tells the screens that draw their own cards, menus and buttons whether the icon shape is Rectangle, so they
 * drop their rounded corners too. The activity sets it from the preference.
 */
object Corners {
    var square = false

    fun radius(value: Float) = if (square) 0f else value
}
