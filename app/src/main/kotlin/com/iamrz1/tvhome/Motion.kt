package com.iamrz1.tvhome

import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Motion is how things move, so the home screen, the top bar, all apps and settings all move the same way. */
object Motion {
    const val ANIM_MS = 200L

    /** A full screen opens from a little smaller than it ends, and closes back to that. */
    const val OPEN_SCALE = 0.94f

    /** A focused thing springs up past its size and settles, and goes back without the spring. [extra] adds to the move. */
    fun focus(v: View, active: Boolean, factor: Float, extra: ViewPropertyAnimator.() -> Unit = {}) {
        val s = if (active) factor else 1f
        val anim = v.animate().scaleX(s).scaleY(s)
        anim.extra()
        if (active) {
            anim.setDuration(280).setInterpolator(OvershootInterpolator(1.8f))
        } else {
            anim.setDuration(180).setInterpolator(DecelerateInterpolator())
        }
        anim.start()
    }

    /** Fades [v] in and scales it up from the middle of its parent. */
    fun open(v: View) {
        v.animate().cancel()
        val parent = v.parent as? View
        v.pivotX = (parent?.width ?: v.width) / 2f
        v.pivotY = (parent?.height ?: v.height) / 2f
        v.alpha = 0f
        v.scaleX = OPEN_SCALE
        v.scaleY = OPEN_SCALE
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Fades [v] out and scales it down, then runs [end]. */
    fun close(v: View, end: () -> Unit) {
        v.animate().cancel()
        v.animate().alpha(0f).scaleX(OPEN_SCALE).scaleY(OPEN_SCALE).setDuration(ANIM_MS)
            .setInterpolator(AccelerateInterpolator()).withEndAction(end).start()
    }
}
