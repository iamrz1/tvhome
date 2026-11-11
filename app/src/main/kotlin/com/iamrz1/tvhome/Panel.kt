package com.iamrz1.tvhome

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Row is one entry of a panel. [checked] shows a check mark, and [keepOpen] keeps the panel open after a click. */
class Row(
    val label: String,
    var checked: Boolean? = null,
    val keepOpen: Boolean = false,
    val onClick: (Row) -> Unit,
)

class PanelHandle(private val dialog: Dialog, private val message: TextView?) {
    fun setMessage(text: String) {
        message?.text = text
    }

    fun dismiss() = dialog.dismiss()
}

/** Panel draws menus like the context menu of the stock Android TV launcher: a light card over an 80% black scrim. */
object Panel {
    private val BACKGROUND = Color.parseColor("#EEEEEE")
    private val FOCUSED = Color.parseColor("#CFCFCF")
    private val TEXT = Color.parseColor("#212121")
    private val HEADER = Color.parseColor("#757575")
    private const val WIDTH_DP = 280
    private var current: Dialog? = null

    fun dismiss() {
        current?.dismiss()
    }

    fun show(
        activity: Activity,
        title: String?,
        message: String?,
        rows: List<Row>,
        anchor: View? = null,
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null,
    ): PanelHandle {
        val density = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * density + 0.5f).toInt()
        val width = px(WIDTH_DP)

        current?.dismiss()
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(8), 0, px(8))
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = px(6).toFloat()
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        fun text(value: String, sp: Float, color: Int, top: Int, bottom: Int) = TextView(activity).apply {
            this.text = value
            textSize = sp
            setTextColor(color)
            setPadding(px(16), px(top), px(16), px(bottom))
        }
        title?.let { content.addView(text(it, 12f, HEADER, 4, 8)) }
        var messageView: TextView? = null
        message?.let { messageView = text(it, 14f, TEXT, 4, 8).also(content::addView) }
        val views = rows.map { row -> rowView(activity, row, dialog, density).also(content::addView) }

        dialog.setContentView(content, ViewGroup.LayoutParams(width, WRAP_CONTENT))
        dialog.setCancelable(cancelable)
        dialog.setOnDismissListener {
            if (current === dialog) current = null
            onDismiss?.invoke()
        }
        dialog.setOnShowListener {
            val first = rows.indexOfFirst { it.checked == true }.takeIf { it >= 0 } ?: 0
            views.getOrNull(first)?.requestFocus()
        }
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.8f)
            val lp = w.attributes
            lp.width = width
            lp.height = WRAP_CONTENT
            place(lp, activity, content, width, anchor?.takeIf { it.isAttachedToWindow }, ::px)
            w.attributes = lp
        }
        current = dialog
        dialog.show()
        return PanelHandle(dialog, messageView)
    }

    // like the stock launcher, open above the anchor when there is room and below it otherwise
    private fun place(
        lp: WindowManager.LayoutParams,
        activity: Activity,
        content: View,
        width: Int,
        anchor: View?,
        px: (Int) -> Int,
    ) {
        if (anchor == null) {
            lp.gravity = Gravity.CENTER
            return
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = content.measuredHeight
        val screen = activity.resources.displayMetrics
        val margin = px(24)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (loc[0] + anchor.width / 2 - width / 2).coerceIn(margin, screen.widthPixels - width - margin)
        val above = loc[1] - height - px(12)
        lp.y = if (above >= margin) above else (loc[1] + anchor.height + px(12)).coerceAtMost(screen.heightPixels - height - margin)
    }

    private fun rowView(activity: Activity, row: Row, dialog: Dialog, density: Float): View {
        fun px(v: Int) = (v * density + 0.5f).toInt()
        val check = ImageView(activity).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(TEXT)
            layoutParams = LinearLayout.LayoutParams(px(20), px(20)).apply { marginStart = px(12) }
            visibility = if (row.checked == true) View.VISIBLE else View.GONE
        }
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48)
            setPadding(px(16), 0, px(16), 0)
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(FOCUSED))
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(FOCUSED))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            addView(TextView(activity).apply {
                text = row.label
                textSize = 14f
                setTextColor(TEXT)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(check)
            setOnClickListener {
                row.onClick(row)
                if (row.keepOpen) {
                    check.visibility = if (row.checked == true) View.VISIBLE else View.GONE
                } else {
                    dialog.dismiss()
                }
            }
        }
    }
}
