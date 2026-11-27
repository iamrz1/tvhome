package com.iamrz1.tvhome

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** What the settings screen needs from the home screen. */
interface SettingsHost {
    val palette: Palette

    /** Applies the saved preferences to the home screen. */
    fun apply()

    /** A half size picture of the home screen. */
    fun snapshot(): Bitmap

    fun closed()

    fun checkUpdates(anchor: View)

    val hasProjectorSettings: Boolean

    val hasHdmi: Boolean

    fun openSystemSettings()

    fun openProjectorSettings()

    fun searchLocation(anchor: View)
}

private class Option(
    val label: String,
    val icon: Int? = null,
    val swatch: Wallpaper? = null,
    val checked: Boolean? = null,
    val compact: Boolean = false,
    val onClick: (View) -> Unit,
)

private class Section(val heading: String?, val options: List<Option>)

private enum class Page(val label: String, val icon: Int) {
    WALLPAPER("Wallpaper", R.drawable.ic_image),
    LOOK("Look", R.drawable.ic_theme),
    DOCK("Dock", R.drawable.ic_dock),
    TOP_BAR("Top bar", R.drawable.ic_tune),
    SYSTEM("System", R.drawable.ic_settings),
    PROJECTOR("Projector", R.drawable.ic_projector),
    UPDATES("Updates", R.drawable.ic_update),
}

/**
 * SettingsScreen is a full screen over the home screen: a picture of the home screen on top, and below it a row of
 * categories. A category shows its options as tiles. Each tile applies at once and the picture follows.
 */
class SettingsScreen(
    private val activity: Activity,
    private val prefs: Prefs,
    private val host: SettingsHost,
) {
    val view = FrameLayout(activity)
    val isOpen get() = view.visibility == View.VISIBLE

    private val ui = Handler(Looper.getMainLooper())
    private val preview = ImageView(activity)
    private val title = TextView(activity)
    private val hint = TextView(activity)
    private val body = LinearLayout(activity)
    private var shown: Bitmap? = null
    private var page: Page? = null
    private var lightTab = false

    // the tile that gets focus after a render, as a section and an index in it. -1 picks the checked one.
    private var focusSection = 0
    private var focusIndex = -1

    init {
        view.visibility = View.GONE
        view.isClickable = true
        preview.scaleType = ImageView.ScaleType.FIT_XY
        preview.clipToOutline = true
        preview.outlineProvider = rounded(12)
        view.addView(preview, FrameLayout.LayoutParams(dp(400), dp(225), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(16)
        })

        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD
        hint.textSize = 14f
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(hint)
        }
        body.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(body)
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        view.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            topMargin = dp(252)
            marginStart = dp(32)
            marginEnd = dp(32)
        })
    }

    fun show() {
        page = null
        focusSection = 0
        focusIndex = -1
        lightTab = Wallpapers.get(prefs.wallpaper).light
        view.visibility = View.VISIBLE
        render()
        refreshPreview(0)
    }

    private fun hide() {
        ui.removeCallbacksAndMessages(null)
        view.visibility = View.GONE
        host.closed()
    }

    /** back goes up one level and closes the screen from the top level. It returns false when the screen is closed. */
    fun back(): Boolean {
        if (!isOpen) return false
        val from = page
        if (from == null) {
            hide()
        } else {
            page = null
            focusSection = 0
            focusIndex = from.ordinal
            render()
        }
        return true
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(radiusDp: Int) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
            outline.setRoundRect(0, 0, v.width, v.height, Corners.radius(dp(radiusDp).toFloat()))
        }
    }

    /** refresh redraws the page after something outside the screen changed a setting. */
    fun refresh() {
        render()
        refreshPreview(150)
    }

    private fun changed() {
        host.apply()
        render()
        refreshPreview(150)
    }

    private fun refreshPreview(delay: Long) {
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({
            if (!isOpen) return@postDelayed
            // The settings screen would be in the picture, so draw it clear. Hiding it would drop the focus.
            view.alpha = 0f
            val bitmap = host.snapshot()
            view.alpha = 1f
            preview.setImageBitmap(bitmap)
            shown?.recycle()
            shown = bitmap
        }, delay)
    }

    private fun topOptions() = Page.entries.filter { it != Page.PROJECTOR || host.hasProjectorSettings }.map { p ->
        Option(p.label, icon = p.icon) { v ->
            if (p == Page.UPDATES) {
                host.checkUpdates(v)
            } else if (p == Page.SYSTEM) {
                host.openSystemSettings()
            } else if (p == Page.PROJECTOR) {
                host.openProjectorSettings()
            } else {
                page = p
                focusSection = 0
                focusIndex = -1
                render()
            }
        }
    }

    private fun sections(of: Page): List<Section> = when (of) {
        Page.WALLPAPER -> listOf(
            Section(null, listOf(
                Option("Light", checked = lightTab, compact = true) { lightTab = true; render() },
                Option("Dark", checked = !lightTab, compact = true) { lightTab = false; render() },
            )),
            Section(null, Wallpapers.all.withIndex().filter { it.value.light == lightTab }.map { (i, w) ->
                Option(w.name, swatch = w, checked = i == prefs.wallpaper) {
                    prefs.wallpaper = i
                    changed()
                }
            }),
        )
        Page.LOOK -> listOf(
            Section("Theme", ThemeMode.entries.map { mode ->
                Option(mode.label, checked = mode == prefs.themeMode) {
                    prefs.themeMode = mode
                    changed()
                }
            }),
            Section("Icon shape", IconShape.entries.map { shape ->
                Option(shape.label, checked = shape == prefs.iconShape) {
                    prefs.iconShape = shape
                    changed()
                }
            }),
            Section("Focus highlight", FocusStyle.entries.map { style ->
                Option(style.label, checked = style == prefs.focusStyle) {
                    prefs.focusStyle = style
                    changed()
                }
            }),
        )
        Page.DOCK -> listOf(
            Section("Opacity", OPACITIES.map { pct ->
                Option("$pct%", checked = pct == prefs.dockOpacity) {
                    prefs.dockOpacity = pct
                    changed()
                }
            }),
            Section("Apps", listOf(Option("Reset dock", icon = R.drawable.ic_dock) {
                prefs.dock = Prefs.DEFAULT_DOCK
                changed()
            })),
        )
        Page.TOP_BAR -> listOf(
            Section("Show in the top bar", PillItem.entries.filter { it != PillItem.HDMI || host.hasHdmi }.map { item ->
                Option(item.label, checked = item in prefs.pill) {
                    val on = prefs.pill.toMutableSet()
                    if (item in on) on -= item else on += item
                    prefs.pill = on
                    changed()
                }
            }),
            Section("Weather", listOf(
                Option("Place\n${prefs.weatherPlace?.name ?: "Not set"}", icon = R.drawable.ic_w_sun) { v -> host.searchLocation(v) },
                Option(if (prefs.fahrenheit) "Fahrenheit" else "Celsius", icon = R.drawable.ic_theme) {
                    prefs.fahrenheit = !prefs.fahrenheit
                    changed()
                },
            )),
        )
        Page.SYSTEM, Page.PROJECTOR, Page.UPDATES -> emptyList()
    }

    private fun render() {
        val p = host.palette
        preview.invalidateOutline()
        view.setBackgroundColor(p.settingsBg)
        title.setTextColor(p.optionText)
        title.text = page?.label ?: "Settings"
        hint.setTextColor(p.settingsMuted)
        hint.text = if (page == null) "Press Back to exit" else "Press Back to return"

        body.removeAllViews()
        val all = page?.let(::sections) ?: listOf(Section(null, topOptions()))
        var focus: View? = null
        all.forEachIndexed { s, section ->
            section.heading?.let { text ->
                body.addView(TextView(activity).apply {
                    this.text = text
                    textSize = 15f
                    setTextColor(p.settingsMuted)
                    setPadding(dp(4), dp(10), 0, dp(2))
                })
            }
            // a focused tile is scaled up, so the row must not clip it at its padding
            val row = LinearLayout(activity).apply {
                clipChildren = false
                clipToPadding = false
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            val wanted = if (s == focusSection) focusIndex else -2
            section.options.forEachIndexed { i, option ->
                val tile = optionView(option, p)
                tile.setOnClickListener {
                    focusSection = s
                    focusIndex = i
                    option.onClick(it)
                }
                row.addView(tile)
                val pick = wanted == i || (wanted == -1 && option.checked == true)
                if (pick && focus == null) focus = tile
            }
            body.addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(row)
            })
        }
        (focus ?: firstTile())?.requestFocus()
    }

    private fun firstTile(): View? {
        val scroll = body.getChildAt(if (body.getChildAt(0) is TextView) 1 else 0) as? HorizontalScrollView
        return (scroll?.getChildAt(0) as? ViewGroup)?.getChildAt(0)
    }

    private fun optionView(o: Option, p: Palette): View {
        val swatch = o.swatch
        val cell = FrameLayout(activity)
        cell.layoutParams = when {
            o.compact -> LinearLayout.LayoutParams(dp(110), dp(40))
            swatch != null -> LinearLayout.LayoutParams(dp(112), dp(80))
            else -> LinearLayout.LayoutParams(dp(112), dp(84))
        }.apply { marginEnd = dp(10) }
        cell.isFocusable = true
        cell.clipToOutline = true
        cell.outlineProvider = rounded(if (o.compact) 20 else 14)
        cell.contentDescription = o.label

        val label = TextView(activity).apply {
            text = o.label
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            textSize = if (o.compact) 15f else 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val icon = o.icon?.let { res ->
            ImageView(activity).apply { setImageResource(res) }
        }
        val check = ImageView(activity).apply {
            setImageResource(R.drawable.ic_check)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            visibility = if (o.checked == true && !o.compact) View.VISIBLE else View.GONE
        }

        if (swatch != null) {
            cell.background = swatch.drawable()
            label.setTextColor(Color.WHITE)
            label.setShadowLayer(dp(3).toFloat(), 0f, 1f, 0xAA000000.toInt())
            label.textSize = 12f
            cell.addView(label, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = dp(6)
            })
        } else {
            val inner = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), 0, dp(6), 0)
            }
            icon?.let { inner.addView(it, LinearLayout.LayoutParams(dp(28), dp(28)).apply { bottomMargin = dp(6) }) }
            inner.addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            cell.addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        cell.addView(check, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(6)
            marginEnd = dp(6)
        })

        // a selected tab looks selected without a check mark
        val selectedTab = o.compact && o.checked == true
        fun style(focused: Boolean) {
            val fg = if (focused) p.optionTextFocus else p.optionText
            val bg = if (focused) p.optionFocus else p.option
            if (swatch == null) {
                cell.background = ColorDrawable(
                    when {
                        focused -> p.optionFocus
                        selectedTab -> (p.optionFocus and 0x00FFFFFF) or 0x40000000
                        else -> p.option
                    },
                )
                label.setTextColor(fg)
                icon?.setColorFilter(fg)
            }
            check.background = GradientDrawable().apply {
                shape = if (Corners.square) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
                setColor(fg)
            }
            check.setColorFilter(bg)
            // a swatch shows its focus as a frame, since its color is the artwork
            cell.foreground = if (swatch != null && focused) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(6), p.optionFocus)
                cornerRadius = Corners.radius(dp(14).toFloat())
            } else null
            cell.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
        }
        style(false)
        cell.setOnFocusChangeListener { _, f -> style(f) }
        return cell
    }

    private companion object {
        val OPACITIES = listOf(0, 20, 40, 60, 80, 100)
    }
}
