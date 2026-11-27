package com.iamrz1.tvhome

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** LocationSearch asks for a place name, looks it up and lets the user pick one of the matches. */
class LocationSearch(
    private val activity: Activity,
    private val palette: Palette,
    private val anchor: View?,
    private val onPick: (Place) -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())

    fun show() {
        val density = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * density + 0.5f).toInt()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val input = EditText(activity).apply {
            hint = "City or town"
            setSingleLine()
            textSize = 20f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(palette.optionText)
            setHintTextColor(palette.settingsMuted)
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(20), px(24), px(20))
            background = GradientDrawable().apply {
                setColor(palette.option)
                cornerRadius = Corners.radius(px(14).toFloat())
            }
            addView(TextView(activity).apply {
                text = "Search for a place"
                textSize = 14f
                setTextColor(palette.settingsMuted)
            })
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT))
            addView(TextView(activity).apply {
                text = "Type a name, then press search on the keyboard."
                textSize = 12f
                setTextColor(palette.settingsMuted)
                setPadding(0, px(8), 0, 0)
            })
        }
        dialog.setContentView(card, ViewGroup.LayoutParams(px(380), WRAP_CONTENT))
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.8f)
            w.setLayout(px(380), WRAP_CONTENT)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        input.setOnEditorActionListener { _, action, _ ->
            val query = input.text.toString().trim()
            if (action != EditorInfo.IME_ACTION_SEARCH && action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (query.isNotEmpty()) {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                dialog.dismiss()
                search(query)
            }
            true
        }
        dialog.show()
        input.requestFocus()
    }

    private fun search(query: String) {
        toast("Searching...")
        thread {
            val result = runCatching { Weather.search(query) }
            ui.post {
                if (activity.isFinishing) return@post
                result
                    .onFailure {
                        Log.e("tvhome", "place search failed", it)
                        toast("Could not search: ${it.message ?: it.javaClass.simpleName}")
                    }
                    .onSuccess { places ->
                        if (places.isEmpty()) toast("No place found for $query") else choose(places)
                    }
            }
        }
    }

    private fun choose(places: List<Place>) {
        Panel.show(activity, "Choose a place", null, places.map { place -> Row(place.name) { onPick(place) } }, anchor)
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
