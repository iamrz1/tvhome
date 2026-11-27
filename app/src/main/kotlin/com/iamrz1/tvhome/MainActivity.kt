package com.iamrz1.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var palette: Palette
    private lateinit var root: FrameLayout
    private lateinit var wallpaperView: View
    private lateinit var pill: LinearLayout
    private lateinit var dockScroll: HorizontalScrollView
    private lateinit var dockRow: LinearLayout
    private lateinit var allApps: View
    private lateinit var allBg: View
    private lateinit var allContent: LinearLayout
    private lateinit var allTitle: TextView
    private lateinit var grid: GridView
    private lateinit var settings: SettingsScreen
    private var weatherBox: LinearLayout? = null
    private var weatherIcon: ImageView? = null
    private var weatherTemp: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, WEATHER_MS)
        }
    }

    private val adapter = AppsAdapter()
    private val icons = HashMap<String, Drawable>()
    private val tileColors = HashMap<String, IntArray>()
    private var wallpaper: Wallpaper.Gradient? = null
    private var blurredFor: Wallpaper.Gradient? = null
    private var rowHeight = 0
    private var selectedCell: View? = null
    private var customizeButton: View? = null
    private var apps: List<AppEntry> = emptyList()
    private var allAppsOpen = false
    private var dockFocus = 0
    private var movingPkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        palette = Palette.resolve(prefs.themeMode, resources.configuration, prefs.dockOpacity)
        Panel.dark = palette.dark
        Corners.square = prefs.iconShape == IconShape.RECTANGLE
        root = FrameLayout(this)
        // The wallpaper is a view of its own, drawn once into a hardware layer. A wallpaper with spots of light is several
        // full screen shaders, and it was drawn again for every frame of whatever moved over it.
        wallpaperView = View(this).apply { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        root.addView(wallpaperView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setWallpaper()
        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshGlass() }
        buildPill()
        buildDock()
        buildAllApps()
        settings = SettingsScreen(this, prefs, host)
        root.addView(settings.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    override fun onResume() {
        super.onResume()
        apps = Apps.load(this)
        icons.clear()
        tileColors.clear()
        adapter.notifyDataSetChanged()
        rebuildDock()
        ui.removeCallbacks(weatherTick)
        weatherTick.run()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(weatherTick)
        endMove()
    }

    // Home on the home screen does nothing, except that it closes an open menu or the all apps screen
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Panel.dismiss()
        if (settings.isOpen) closeSettings()
        hideAllApps()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (prefs.themeMode == ThemeMode.AUTO) applyTheme()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settings.back()) return
        if (movingPkg != null) endMove() else if (allAppsOpen) hideAllApps()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (movingPkg != null) {
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveBy(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveBy(1); return true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { endMove(); return true }
                }
            } else {
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !allAppsOpen && dockIndex(currentFocus) >= 0) {
                    showAllApps()
                    return true
                }
                // up from the first row of the grid goes back to the dock screen
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP && allAppsOpen && grid.selectedItemPosition in 0 until COLUMNS) {
                    hideAllApps()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun setWallpaper() {
        wallpaper = Wallpapers.get(prefs.wallpaper).drawable().also { wallpaperView.background = it }
        blurredFor = null
    }

    // The glass layers draw a blurred copy of the wallpaper. It is made again when the wallpaper or the screen changes.
    private fun refreshGlass() {
        val wp = wallpaper ?: return
        if (!glass() || root.width <= 0) return
        if (blurredFor === wp && GlassBlur.matches(root.width, root.height)) return
        GlassBlur.update(wp, root.width, root.height)
        blurredFor = wp
        pill.invalidate()
        dockScroll.invalidate()
        allBg.invalidate()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = Corners.radius(dp(radiusDp).toFloat())
    }

    private fun circle() = prefs.iconShape == IconShape.CIRCLE

    // the corner radius of a tile: none for a rectangle, and half the height for a circle, which is as wide as it is high
    private fun tileRadius(height: Int = TILE_H) = when (prefs.iconShape) {
        IconShape.RECTANGLE -> 0f
        IconShape.ROUNDED -> dp(if (glass()) 20 else 14).toFloat()
        IconShape.CIRCLE -> height / 2f
    }

    private fun tileWidth(width: Int = TILE_W, height: Int = TILE_H) = if (circle()) height else width

    private fun glass() = prefs.homeStyle == HomeStyle.GLASS

    // the text and icons on glass are white, whatever the theme
    private fun ink() = if (glass()) Color.WHITE else palette.label

    private fun inkMuted() = if (glass()) withAlpha(Color.WHITE, 0xEE) else palette.muted

    private fun inkFocus() = if (glass()) Color.WHITE else palette.focus

    // The glass dock holds five tiles and the round all apps button across the screen, so its tiles are sized from the
    // width that is left. They look the same on any screen.
    private fun dockTileW() = if (glass()) {
        val content = resources.displayMetrics.widthPixels - 2 * dp(GLASS_MARGIN) - 2 * dp(GLASS_PAD)
        ((content - 5 * dockGap()) / (MAX_DOCK + 9 / 16f)).toInt()
    } else {
        TILE_W
    }

    private fun dockTileH() = if (glass()) dockTileW() * 9 / 16 else TILE_H

    // the room around a dock tile is for the highlight
    private fun dockRoom() = if (glass()) dp(22) else ROOM

    private fun dockGap() = if (glass()) dp(18) else GAP

    private fun lightFont() = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private fun mediumFont() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // a circle shows the name under it all the time, and the lift style shows the name of the selected tile
    private fun namesBelow() = circle() || liftStyle()

    private fun nameShown(selected: Boolean) = circle() || (liftStyle() && selected)

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun applyTheme() {
        palette = Palette.resolve(prefs.themeMode, resources.configuration, prefs.dockOpacity)
        Panel.dark = palette.dark
        Corners.square = prefs.iconShape == IconShape.RECTANGLE
        refreshGlass()
        pill.background = pillBackground()
        layoutDock()
        styleAllApps()
        fillPill()
        rebuildDock()
        // new cells, because the grid rows are taller when the focus style shows names
        grid.adapter = adapter
    }

    private fun liftStyle() = prefs.focusStyle == FocusStyle.LIFT

    private fun focusScale() = if (liftStyle()) LIFT_SCALE else FOCUS_SCALE

    // the dock card has a thin edge, so it still reads as a card when it is clear
    private fun dockBackground(): Drawable = if (glass()) {
        // the card stops above the band where the name of the focused tile is
        InsetDrawable(
            GlassDrawable(dockScroll, { Corners.radius(dp(26).toFloat()) }, look(Pane.DOCK).tint, look(Pane.DOCK).top, look(Pane.DOCK).bottom),
            0, 0, 0, dp(NAME_BAND),
        )
    } else {
        GradientDrawable().apply {
            setColor(palette.dock)
            // the dock follows the icons when they are rectangles
            cornerRadius = Corners.radius(dp(28).toFloat())
            setStroke(dp(1), withAlpha(Color.WHITE, if (palette.dark) 0x26 else 0x99))
        }
    }

    // The panes of glass. On the light theme they are frosted and tinted with violet, as in the design. On the dark theme
    // they are smoked, which is a dark tint with less light on top.
    private enum class Pane { PILL, DOCK, SHEET, DIM, CAPSULE, BUTTON }

    private class Look(val tint: Int, val top: Int, val bottom: Int)

    private fun look(pane: Pane): Look = if (palette.dark) {
        when (pane) {
            Pane.PILL -> Look(Color.argb(0x99, 8, 6, 24), 0x26, 0x08)
            Pane.DOCK -> Look(Color.argb(0x73, 8, 6, 24), 0x2E, 0x0C)
            Pane.SHEET -> Look(Color.argb(0x8C, 8, 6, 24), 0x28, 0x0A)
            Pane.DIM -> Look(Color.argb(0xA6, 4, 3, 14), 0, 0)
            Pane.CAPSULE -> Look(Color.argb(0xA6, 8, 6, 24), 0x2E, 0x0C)
            Pane.BUTTON -> Look(Color.argb(0x59, 8, 6, 24), 0x38, 0x12)
        }
    } else {
        when (pane) {
            Pane.PILL -> Look(Color.argb(0x42, 44, 22, 110), 0x42, 0x0F)
            Pane.DOCK -> Look(Color.argb(0x1A, 44, 22, 110), 0x4D, 0x1A)
            Pane.SHEET -> Look(Color.argb(0x33, 44, 22, 110), 0x42, 0x14)
            Pane.DIM -> Look(Color.argb(0x57, 24, 10, 64), 0, 0)
            Pane.CAPSULE -> Look(Color.argb(0x38, 44, 22, 110), 0x4D, 0x1A)
            Pane.BUTTON -> Look(Color.argb(0x14, 255, 255, 255), 0x57, 0x1F)
        }
    }

    private fun pillBackground(): Drawable {
        val l = look(Pane.PILL)
        return if (glass()) GlassDrawable(pill, { -1f }, l.tint, l.top, l.bottom) else round(palette.pill, 30)
    }

    // what a focused top bar button shows: a white ring, or a soft fill
    private fun pillMark(): Drawable {
        val active = GradientDrawable().apply {
            shape = if (Corners.square) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
            if (liftStyle()) {
                setColor(withAlpha(if (glass()) Color.WHITE else palette.focus, 0x33))
            } else {
                setColor(palette.focusFill)
                setStroke(dp(3), Color.WHITE)
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), active)
            addState(intArrayOf(), GradientDrawable())
        }
    }

    // What a dock or grid cell draws behind its tile while the tile is focused (activated) or selected. Both styles
    // are drawn in the room around the tile, so the artwork is never covered and nothing is cut off.
    private fun cellBackground(width: Int = TILE_W, height: Int = TILE_H, soft: Boolean = false): Drawable {
        val mark = focusMark(width, height, soft)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), mark)
            addState(intArrayOf(android.R.attr.state_selected), mark)
            addState(intArrayOf(), GradientDrawable())
        }
    }

    // A soft halo is for the grid, where the tiles are close together. The neighbor on the left is drawn before the focused
    // cell, so a halo that reaches it shows as a dark line on its edge. This one is narrower and fainter.
    private fun focusMark(width: Int, height: Int, soft: Boolean = false): Drawable {
        val w = tileWidth(width, height)
        val r = tileRadius(height)
        return if (liftStyle() && soft) {
            GlowDrawable(Color.argb(0x5C, 20, 8, 60), r, dp(9).toFloat(), w - dp(18), height, dp(9).toFloat())
        } else if (liftStyle() && glass()) {
            GlowDrawable(Color.argb(0x80, 20, 8, 60), r, dp(14).toFloat(), w, height, dp(10).toFloat())
        } else if (liftStyle()) {
            GlowDrawable(withAlpha(Color.BLACK, 0x80), r, dp(8).toFloat(), w, height, dp(3).toFloat())
        } else {
            ringFrame(Color.WHITE, 3, w, height)
        }
    }

    // draws the halo of a grid cell once, before the first tile is focused, so the focus animation does not stutter
    private fun warmGrid() {
        val cellW = (grid.width - grid.paddingLeft - grid.paddingRight) / COLUMNS
        val cellH = if (rowHeight > 0) rowHeight else baseRowHeight()
        if (cellW > 0) (focusMark(TILE_W, TILE_H, soft = true) as? GlowDrawable)?.preload(cellW, cellH)
    }

    private fun ringFrame(color: Int, strokeDp: Int, width: Int = tileWidth(), height: Int = TILE_H) =
        RingDrawable(color, dp(strokeDp).toFloat(), dp(2).toFloat(), tileRadius(height), width, height)

    // a focused tile springs up past its size and settles
    private fun scaleTo(v: View, active: Boolean, factor: Float = focusScale()) {
        // on glass a lifted tile also rises
        Motion.focus(v, active, factor) {
            if (glass() && liftStyle()) translationY(if (active) -dp(8).toFloat() else 0f)
        }
    }

    // What a tile draws over its artwork: on glass, a white ring while it is lifted, and nothing otherwise. A thin light
    // line round every tile looked rough, since it sat on the smoothed edge of the artwork and made a second edge.
    private fun showShine(tile: View?, on: Boolean, height: Int = TILE_H) {
        tile ?: return
        if (!glass() || !on || !liftStyle()) {
            tile.foreground = null
            return
        }
        val radius = if (tile.tag == ROUND) (if (Corners.square) 0f else height / 2f) else tileRadius(height)
        tile.foreground = GradientDrawable().apply {
            setStroke(dp(2), withAlpha(Color.WHITE, 0xDB))
            cornerRadius = radius
        }
    }

    private fun icon(app: AppEntry): Drawable =
        icons.getOrPut(app.pkg) { app.info.loadIcon(packageManager) }

    private fun buildPill() {
        pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // a focused button is scaled up, and must not be cut off at the edge of the bar
            clipChildren = false
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        pill.background = pillBackground()
        root.addView(
            pill,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(32), dp(40), 0) },
        )
        fillPill()
    }

    private fun fillPill() {
        pill.removeAllViews()
        val on = prefs.pill
        if (PillItem.CLOCK in on) pill.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            textSize = if (glass()) 28f else 22f
            typeface = lightFont()
            setTextColor(inkMuted())
            setPadding(dp(14), 0, dp(10), 0)
        })
        weatherBox = null
        if (PillItem.WEATHER in on && prefs.weatherPlace != null) pill.addView(buildWeather())
        updateWeather()
        if (PillItem.WIFI in on) pill.addView(pillButton(R.drawable.ic_wifi, "Wi-Fi") {
            open(Settings.ACTION_WIFI_SETTINGS)
        })
        // Android TV settings has no Bluetooth settings screen. Its pairing screen answers CONNECT_INPUT instead.
        if (PillItem.BLUETOOTH in on) pill.addView(pillButton(R.drawable.ic_bluetooth, "Bluetooth") {
            open(Settings.ACTION_BLUETOOTH_SETTINGS, "com.google.android.intent.action.CONNECT_INPUT")
        })
        if (PillItem.HDMI in on && Projector.hasHdmi(this)) pill.addView(pillButton(R.drawable.ic_hdmi, "HDMI") {
            Projector.openHdmi(this)
        })
        customizeButton = pillButton(R.drawable.ic_tune, "Customize") { showCustomize() }
        pill.addView(customizeButton)
    }

    private fun buildWeather(): View {
        val icon = ImageView(this).apply { setColorFilter(inkMuted()) }
        val temp = TextView(this).apply {
            textSize = if (glass()) 24f else 20f
            typeface = lightFont()
            setTextColor(inkMuted())
            setPadding(dp(6), 0, 0, 0)
        }
        weatherIcon = icon
        weatherTemp = temp
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(10), 0)
            visibility = View.GONE
            addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(temp)
            weatherBox = this
        }
    }

    // shows the saved weather in place, so a refresh never rebuilds the pill and drops its focus
    private fun updateWeather() {
        val box = weatherBox ?: return
        val place = prefs.weatherPlace
        val forecast = prefs.forecast?.takeIf { it.place == place?.key }
        if (place == null || forecast == null) {
            box.visibility = View.GONE
            return
        }
        val degrees = if (prefs.fahrenheit) forecast.celsius * 9 / 5 + 32 else forecast.celsius
        weatherIcon?.setImageResource(Weather.icon(forecast.code))
        weatherTemp?.text = "${degrees.roundToInt()}°"
        box.contentDescription = place.name
        box.visibility = View.VISIBLE
    }

    private fun refreshWeather() {
        val place = prefs.weatherPlace ?: return
        if (PillItem.WEATHER !in prefs.pill) return
        val saved = prefs.forecast
        if (saved != null && saved.place == place.key && System.currentTimeMillis() - saved.at < WEATHER_MS) return
        thread {
            val forecast = runCatching { Weather.current(place) }
                .onFailure { Log.e("tvhome", "weather failed", it) }
                .getOrNull() ?: return@thread
            runOnUiThread {
                if (isFinishing || prefs.weatherPlace?.key != place.key) return@runOnUiThread
                prefs.forecast = forecast
                updateWeather()
            }
        }
    }

    private fun pillButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(inkMuted())
        contentDescription = desc
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4); marginEnd = dp(4) }
        isFocusable = true
        foreground = pillMark()
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, f ->
            (v as ImageView).setColorFilter(if (f) inkFocus() else inkMuted())
            scaleTo(v, f)
        }
    }

    // opens the first action that some app on the device handles
    private fun open(vararg actions: String) {
        for (action in actions) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            }
        }
        Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
    }

    private fun buildDock() {
        dockScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        dockRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            // without this the row clips the scaled cells at its padding, which cuts the highlight at both ends
            clipToPadding = false
        }
        dockScroll.addView(dockRow)
        root.addView(dockScroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        layoutDock()
    }

    // Both docks are a card at the bottom. The glass one sits higher, has the round all apps button in its row, and keeps
    // a band under it for the name of the focused tile.
    private fun layoutDock() {
        dockScroll.background = dockBackground()
        // A background that has padding of its own, like the inset glass card, changes the padding of the view, and a
        // background that has none leaves that padding behind. So the padding is always set here.
        dockScroll.setPadding(0, 0, 0, 0)
        val lp = dockScroll.layoutParams as FrameLayout.LayoutParams
        if (glass()) {
            // the card keeps its place, the band is under it
            lp.setMargins(dp(GLASS_MARGIN), 0, dp(GLASS_MARGIN), dp(110 - NAME_BAND))
            // the first cell has half a gap of margin, which is part of the space to the edge of the card
            val side = dp(GLASS_PAD) - dockGap() / 2
            dockRow.setPadding(side, dp(8), side, dp(8 + NAME_BAND))
        } else {
            lp.setMargins(dp(20), 0, dp(20), dp(32))
            dockRow.setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        dockScroll.layoutParams = lp
    }

    private fun visibleDock(): List<String> {
        val installed = apps.mapTo(HashSet()) { it.pkg }
        return prefs.dock.filter { it in installed }
    }

    // the index of the dock cell that holds [v], or -1 when it is not part of the dock
    private fun dockIndex(v: View?): Int {
        var cur = v
        while (cur != null && cur.parent !== dockRow) cur = cur.parent as? View
        return if (cur == null) -1 else dockRow.indexOfChild(cur)
    }

    private fun rebuildDock(focusIndex: Int = dockIndex(currentFocus)) {
        val byPkg = apps.associateBy { it.pkg }
        dockRow.removeAllViews()
        for (pkg in visibleDock()) byPkg[pkg]?.let { dockRow.addView(dockCell(it)) }
        if (glass()) dockRow.addView(allAppsCell())
        if (dockRow.childCount == 0) dockRow.addView(TextView(this).apply {
            text = "Press Down for all apps"
            setTextColor(palette.muted)
            textSize = 18f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        if (!allAppsOpen && !settings.isOpen) {
            dockRow.getChildAt(focusIndex.coerceIn(0, dockRow.childCount - 1))?.requestFocus()
        }
    }

    // The tile does not clip to its outline. That clip has no smooth edge, and it cut the rounded corners of the banner and
    // of the edge line into steps. The banner draws its own rounded corners, and so does the background.
    private fun newTile() = FrameLayout(this)

    // The colors of a tile that has no banner: a gradient of the hue of the icon, pale on the light theme and deep on the
    // dark one.
    private fun blend(from: Int, to: Int, part: Float): Int {
        fun mix(a: Int, b: Int) = (a + (b - a) * part).toInt()
        return Color.rgb(mix(Color.red(from), Color.red(to)), mix(Color.green(from), Color.green(to)), mix(Color.blue(from), Color.blue(to)))
    }

    private fun iconGradient(app: AppEntry): IntArray = tileColors.getOrPut(app.pkg) {
        val size = 24
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val icon = app.info.loadIcon(packageManager)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bmp))
        var r = 0f
        var g = 0f
        var b = 0f
        var weight = 0f
        val hsv = FloatArray(3)
        for (x in 0 until size) for (y in 0 until size) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            Color.colorToHSV(c, hsv)
            // saturated pixels say more about the icon than white or black ones
            val w = 0.05f + hsv[1] * hsv[2]
            r += Color.red(c) * w
            g += Color.green(c) * w
            b += Color.blue(c) * w
            weight += w
        }
        if (weight == 0f) return@getOrPut intArrayOf(palette.tile, palette.tile)
        val base = Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt())
        if (palette.dark) {
            intArrayOf(blend(base, Color.BLACK, 0.5f), blend(base, Color.BLACK, 0.7f))
        } else {
            intArrayOf(blend(base, Color.WHITE, 0.82f), blend(base, Color.WHITE, 0.62f))
        }
    }

    private fun bindTile(tile: FrameLayout, app: AppEntry, height: Int = TILE_H) {
        tile.removeAllViews()
        val gradient = glass() && !circle()
        tile.background = if (gradient) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR, iconGradient(app)).apply { cornerRadius = tileRadius(height) }
        } else {
            GradientDrawable().apply {
                setColor(palette.tile)
                cornerRadius = tileRadius(height)
            }
        }
        // a circle shows the app icon, because a wide banner does not fit in it
        val art = if (circle()) app.info.loadIcon(packageManager) else app.info.activityInfo.loadBanner(packageManager)
        if (art != null) {
            tile.addView(ImageView(this).apply {
                setImageDrawable(RoundedBanner(art, tileRadius(height), fillCorners = !circle()))
                scaleType = ImageView.ScaleType.FIT_XY
            })
            return
        }
        tile.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // the Apple TV style shows the name of the focused tile only, so the icon can be bigger
                val size = if (glass()) minOf(dp(56), height * 3 / 5) else dp(44)
                addView(ImageView(context).apply {
                    setImageDrawable(icon(app))
                    layoutParams = LinearLayout.LayoutParams(size, size)
                })
                if (!glass()) addView(TextView(context).apply {
                    text = app.label
                    textSize = 14f
                    setTextColor(palette.label)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), dp(6), dp(8), 0)
                })
            },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
    }

    // A dock cell is the tile plus room around it for the highlight. The cell scales, so the highlight scales with the
    // tile. The negative side margins let cells overlap their room, which leaves GAP between two tiles.
    private fun dockCell(app: AppEntry): View {
        val h = dockTileH()
        val cell = dockFrame()
        var name: View? = null
        val tile = newTile().apply {
            bindTile(this, app, h)
            isFocusable = true
            setOnClickListener { Apps.launch(context, app) }
            setOnLongClickListener { showAppMenu(app, it); true }
            setOnFocusChangeListener { _, f -> dockFocused(cell, this, name, f, if (app.pkg == movingPkg) MOVE_SCALE else focusScale()) }
        }
        // the tile is the first child of the cell. The glass style fades the name in under the card.
        cell.addView(tile, FrameLayout.LayoutParams(tileWidth(dockTileW(), h), h, Gravity.CENTER))
        showShine(tile, false, h)
        if (glass()) name = nameUnder(cell, app.label)
        return cell
    }

    // the glass dock ends with a round button that opens all apps
    private fun allAppsCell(): View {
        val h = dockTileH()
        val cell = dockFrame(h)
        val tile = newTile().apply {
            tag = ROUND
            background = GlassDrawable(this, { if (Corners.square) 0f else -1f }, look(Pane.BUTTON).tint, look(Pane.BUTTON).top, look(Pane.BUTTON).bottom, bake = false)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_apps)
                setColorFilter(Color.WHITE)
                setPadding(h / 4, h / 4, h / 4, h / 4)
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            contentDescription = "All apps"
            isFocusable = true
            setOnClickListener { showAllApps() }
            setOnFocusChangeListener { _, f -> dockFocused(cell, this, null, f, focusScale()) }
        }
        cell.addView(tile, FrameLayout.LayoutParams(h, h, Gravity.CENTER))
        showShine(tile, false, h)
        return cell
    }

    private fun dockFrame(width: Int = dockTileW()) = FrameLayout(this).apply {
        val room = dockRoom()
        layoutParams = LinearLayout.LayoutParams(width + 2 * room, dockTileH() + 2 * room)
            .apply { setMargins(dockGap() / 2 - room, 0, dockGap() / 2 - room, 0) }
        clipChildren = false
        background = cellBackground(width, dockTileH())
    }

    // the name hangs below the card, in a glass capsule in the band that the row keeps for it
    private fun nameUnder(cell: FrameLayout, label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        typeface = mediumFont()
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(18), 0, dp(18), 0)
        background = capsule(this)
        alpha = 0f
        // the card ends a little under the cell, and the capsule starts a little under the card
        cell.addView(
            this,
            FrameLayout.LayoutParams(WRAP_CONTENT, dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = -dp(42) },
        )
    }

    // a capsule sits in a cell that is scaled up, so it is drawn as shapes, which stay sharp, and not as a bitmap, which does not
    private fun capsule(view: View) = GlassDrawable(view, { -1f }, look(Pane.CAPSULE).tint, look(Pane.CAPSULE).top, look(Pane.CAPSULE).bottom, bake = false)

    private fun dockFocused(cell: View, tile: View, name: View?, focused: Boolean, factor: Float) {
        cell.isActivated = focused
        showShine(tile, focused, dockTileH())
        name?.animate()?.alpha(if (focused) 1f else 0f)?.setDuration(160)?.start()
        scaleTo(cell, focused, factor)
    }

    private fun tileOf(cell: View?) = (cell as? FrameLayout)?.getChildAt(0) as? FrameLayout

    private fun showAppMenu(app: AppEntry, anchor: View?) {
        val dock = visibleDock().toMutableList()
        val i = dock.indexOf(app.pkg)
        val rows = ArrayList<Row>()
        if (i >= 0) {
            if (dock.size > 1) rows += Row("Move") {
                hideAllApps()
                startMove(app)
            }
            rows += Row("Remove from dock") {
                dock.removeAt(i)
                prefs.dock = dock
                rebuildDock(i)
            }
        } else {
            rows += Row("Add to dock") {
                if (dock.size >= MAX_DOCK) {
                    Toast.makeText(this, "Dock is full right now", Toast.LENGTH_SHORT).show()
                } else {
                    dock.add(app.pkg)
                    prefs.dock = dock
                    rebuildDock()
                }
            }
        }
        rows += Row("App info") { showAppInfo(app) }
        Panel.show(this, null, null, rows, anchor)
    }

    // the system screen of the app, where it can be uninstalled or have its cache cleared
    private fun showAppInfo(app: AppEntry) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    // in move mode the selected app follows Left and Right, and Back (or OK) ends it
    private fun startMove(app: AppEntry) {
        movingPkg = app.pkg
        val idx = visibleDock().indexOf(app.pkg)
        for (i in 0 until dockRow.childCount) {
            dockRow.getChildAt(i).animate().alpha(if (i == idx) 1f else 0.5f).setDuration(120).start()
        }
        val cell = dockRow.getChildAt(idx)
        val tile = tileOf(cell) ?: return
        cell.background = ringFrame(Color.WHITE, 4, tileWidth(dockTileW(), dockTileH()), dockTileH())
        tile.requestFocus()
        scaleTo(cell, true, MOVE_SCALE)
    }

    private fun moveBy(delta: Int) {
        val pkg = movingPkg ?: return
        val dock = visibleDock().toMutableList()
        val from = dock.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in dock.indices) return
        Collections.swap(dock, from, to)
        prefs.dock = dock
        val cell = dockRow.getChildAt(from)
        dockRow.removeViewAt(from)
        dockRow.addView(cell, to)
        tileOf(cell)?.requestFocus()
    }

    private fun endMove() {
        val pkg = movingPkg ?: return
        movingPkg = null
        for (i in 0 until dockRow.childCount) dockRow.getChildAt(i).animate().alpha(1f).setDuration(120).start()
        val cell = dockRow.getChildAt(visibleDock().indexOf(pkg)) ?: return
        cell.background = cellBackground(dockTileW(), dockTileH())
        scaleTo(cell, tileOf(cell)?.isFocused == true)
    }

    private fun buildAllApps() {
        grid = GridView(this).apply {
            numColumns = COLUMNS
            isVerticalScrollBarEnabled = false
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            // A row that has scrolled up must not show over what is above the grid. The layout around the grid clips it at
            // the grid's edge, so the padding is the room for what a selected tile draws outside its cell: the scale, the
            // shadow and the name. The cells do not clip, or their shadow would end in a line at the side of the cell.
            clipChildren = false
            clipToPadding = false
            setPadding(dp(GRID_SIDE), dp(GRID_EDGE), dp(GRID_SIDE), dp(GRID_EDGE))
            adapter = this@MainActivity.adapter
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitRows() }
            setOnItemClickListener { _, _, pos, _ -> Apps.launch(this@MainActivity, apps[pos]) }
            setOnItemLongClickListener { _, view, pos, _ -> showAppMenu(apps[pos], view); true }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                    selectedCell?.let { scaleTo(it, false); showName(it, false); showShine(tileOf(it), false) }
                    selectedCell = v?.also { scaleTo(it, true); showName(it, true); showShine(tileOf(it), true) }
                }

                override fun onNothingSelected(p: AdapterView<*>) {
                    selectedCell?.let { scaleTo(it, false); showName(it, false); showShine(tileOf(it), false) }
                    selectedCell = null
                }
            }
        }
        allTitle = TextView(this).apply {
            text = "All apps"
            textSize = if (glass()) 30f else 24f
            typeface = lightFont()
            setPadding(dp(GRID_SIDE), 0, 0, dp(16 - GRID_EDGE))
        }
        // The background is a view of its own, drawn once into a hardware layer. It is a full screen of glass, which is slow
        // to draw on a weak device, and it must not be drawn again for every frame of a tile that is moving over it.
        allBg = View(this).apply { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        allContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The grid clips at its own edge, which keeps a scrolled row off the title, and its padding is the room that a
            // selected cell needs for its scale. So this layout clips neither its padding nor its child.
            clipChildren = true
            clipToPadding = false
            addView(allTitle)
            addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        allApps = FrameLayout(this).apply {
            visibility = View.GONE
            addView(allBg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(allContent, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        root.addView(allApps, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        styleAllApps()
    }

    // Glass: a dim, blurred wallpaper over the home screen with a sheet of glass on it. Classic: a plain wash.
    private fun styleAllApps() {
        // the title is not needed, and its space goes to the rows
        allTitle.visibility = View.GONE
        if (glass()) {
            allBg.background = LayerDrawable(
                arrayOf(
                    GlassDrawable(allBg, { 0f }, look(Pane.DIM).tint, dim = true, bake = false),
                    InsetDrawable(
                        GlassDrawable(allBg, { Corners.radius(dp(24).toFloat()) }, look(Pane.SHEET).tint, look(Pane.SHEET).top, look(Pane.SHEET).bottom, bake = false),
                        dp(SHEET_SIDE), dp(SHEET_EDGE), dp(SHEET_SIDE), dp(SHEET_EDGE),
                    ),
                ),
            )
            allContent.setPadding(
                dp(SHEET_SIDE + 24 - GRID_SIDE), dp(SHEET_EDGE + 16 - GRID_EDGE), dp(SHEET_SIDE + 24 - GRID_SIDE), dp(SHEET_EDGE + 16 - GRID_EDGE),
            )
        } else {
            allBg.setBackgroundColor(palette.overlay)
            allContent.setPadding(dp(32 - GRID_SIDE), dp(32 - GRID_EDGE), dp(32 - GRID_SIDE), dp(32 - GRID_EDGE))
        }
    }

    private fun showAllApps() {
        if (allAppsOpen) return
        allAppsOpen = true
        dockFocus = dockIndex(currentFocus).coerceAtLeast(0)
        setHomeFocusable(false)
        warmGrid()
        allApps.visibility = View.VISIBLE
        allApps.alpha = 0f
        // it opens over the home screen, which stays behind it, instead of sliding in as a page
        allApps.pivotX = allApps.width / 2f
        allApps.pivotY = allApps.height / 2f
        allApps.scaleX = OPEN_SCALE
        allApps.scaleY = OPEN_SCALE
        allApps.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator()).start()
        pill.animate().alpha(0f).setDuration(ANIM_MS).start()
        dockScroll.animate().alpha(0f).setDuration(ANIM_MS).start()
        grid.setSelection(0)
        grid.requestFocus()
        // the grid makes its cells again when it is laid out, so the selected one is dressed after that
        grid.post { dressSelected() }
    }

    private fun dressSelected() {
        val v = grid.selectedView ?: return
        if (v === selectedCell) return
        selectedCell?.let { scaleTo(it, false); showName(it, false); showShine(tileOf(it), false) }
        selectedCell = v.also { scaleTo(it, true); showName(it, true); showShine(tileOf(it), true) }
    }

    private fun hideAllApps() {
        if (!allAppsOpen) return
        allAppsOpen = false
        setHomeFocusable(true)
        dockRow.getChildAt(dockFocus.coerceIn(0, dockRow.childCount - 1))?.requestFocus()
        allApps.animate().alpha(0f).scaleX(OPEN_SCALE).scaleY(OPEN_SCALE).setDuration(ANIM_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { if (!allAppsOpen) allApps.visibility = View.GONE }
            .start()
        pill.animate().alpha(1f).setDuration(ANIM_MS).start()
        dockScroll.animate().alpha(1f).setDuration(ANIM_MS).start()
    }

    // the pill and dock stay in the layout under the all apps screen, so keep focus from reaching them
    private fun setHomeFocusable(on: Boolean) {
        pill.descendantFocusability = if (on) ViewGroup.FOCUS_BEFORE_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        dockScroll.descendantFocusability =
            if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun showCustomize() {
        Panel.dismiss()
        setHomeFocusable(false)
        settings.show()
    }

    private fun closeSettings() {
        settings.back()
        while (settings.isOpen) settings.back()
    }

    private val host = object : SettingsHost {
        override val palette get() = this@MainActivity.palette

        override fun apply() {
            setWallpaper()
            applyTheme()
            refreshWeather()
        }

        override fun snapshot(): Bitmap {
            val scale = 0.5f
            val bitmap = Bitmap.createBitmap((root.width * scale).toInt(), (root.height * scale).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            // The wallpaper view is a hardware layer, which a picture drawn with this canvas keeps showing as it was. So the
            // wallpaper is drawn here, from the drawable, and the view is left out.
            wallpaperView.alpha = 0f
            wallpaper?.draw(canvas)
            root.draw(canvas)
            wallpaperView.alpha = 1f
            return bitmap
        }

        override fun closed() {
            setHomeFocusable(true)
            customizeButton?.requestFocus()
        }

        override fun checkUpdates(anchor: View) = UpdateFlow(this@MainActivity, anchor).start()

        override val hasProjectorSettings get() = Projector.hasSettings(this@MainActivity)

        override val hasHdmi get() = Projector.hasHdmi(this@MainActivity)

        override fun openSystemSettings() = open(Settings.ACTION_SETTINGS)

        override fun openProjectorSettings() {
            // so that Home shows the home screen again, not this screen
            closeSettings()
            Projector.openSettings(this@MainActivity)
        }

        override fun searchLocation(anchor: View) {
            LocationSearch(this@MainActivity, palette, anchor) { place ->
                prefs.weatherPlace = place
                prefs.forecast = null
                // a place with no weather shown would look like nothing happened
                if (PillItem.WEATHER !in prefs.pill) prefs.pill = prefs.pill + PillItem.WEATHER
                apply()
                settings.refresh()
            }.show()
        }
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                clipChildren = false
                addView(newTile(), FrameLayout.LayoutParams(TILE_W, TILE_H, Gravity.CENTER))
                // the name under a selected tile, for the focus styles that show it
                addView(TextView(context).apply {
                    textSize = 14f
                    typeface = mediumFont()
                    letterSpacing = 0.02f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    visibility = View.INVISIBLE
                    if (glass()) {
                        // a glass capsule under the tile
                        setPadding(dp(12), 0, dp(12), 0)
                        background = capsule(this)
                    }
                }, if (glass()) {
                    FrameLayout.LayoutParams(WRAP_CONTENT, dp(26), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) }
                } else {
                    FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(4) }
                })
            }
            cell.getChildAt(0).layoutParams = FrameLayout.LayoutParams(tileWidth(), TILE_H, Gravity.CENTER)
            cell.layoutParams = AbsListView.LayoutParams(MATCH_PARENT, if (rowHeight > 0) rowHeight else baseRowHeight())
            cell.background = cellBackground(soft = true)
            bindTile(cell.getChildAt(0) as FrameLayout, apps[position])
            showShine(tileOf(cell), false)
            (cell.getChildAt(1) as TextView).apply {
                text = apps[position].label
                setTextColor(ink())
                // A cell that is made for the selected position is not marked as selected yet, so ask the grid.
                visibility = if (nameShown(allAppsOpen && position == grid.selectedItemPosition)) View.VISIBLE else View.INVISIBLE
            }
            showShine(tileOf(cell), allAppsOpen && position == grid.selectedItemPosition)
            return cell
        }
    }

    // the height a row needs: the tile, the room for its highlight and, where it shows, the name under it
    private fun baseRowHeight() = TILE_H + 2 * ROOM + if (namesBelow()) dp(32) else 0

    // The grid shows whole rows only. The rows that fit are counted from the height the grid got, and share that
    // height, so the last row ends where the grid ends and no row is cut off, on any screen size.
    private fun fitRows() {
        val height = grid.height - grid.paddingTop - grid.paddingBottom
        if (height <= 0) return
        val rows = maxOf(1, (height / (baseRowHeight() * MIN_ROW)).toInt())
        val fitted = height / rows
        if (fitted != rowHeight) {
            rowHeight = fitted
            adapter.notifyDataSetChanged()
            grid.post { if (allAppsOpen) dressSelected() }
        }
    }

    private fun showName(cell: View, on: Boolean) {
        (cell as? FrameLayout)?.getChildAt(1)?.visibility = if (nameShown(on)) View.VISIBLE else View.INVISIBLE
    }

    // five tiles and four gaps fit the dock, and ROOM around a tile is what the highlight can use
    private val TILE_W by lazy { dp(156) }
    private val TILE_H by lazy { dp(88) }
    private val ROOM by lazy { dp(12) }
    private val GAP by lazy { dp(18) }
    private val OPEN_SCALE = Motion.OPEN_SCALE

    private companion object {
        // the dock fits this many tiles on one screen
        const val MAX_DOCK = 5
        const val COLUMNS = 5
        const val GRID_SIDE = 20
        const val GLASS_MARGIN = 24
        const val GLASS_PAD = 26
        // The band under the glass dock for the name of the focused tile. The focused cell is scaled up, which moves the
        // name further down, so the band holds it with room to spare.
        const val NAME_BAND = 60
        const val SHEET_SIDE = 28
        const val SHEET_EDGE = 22
        const val ROUND = "round"
        const val GRID_EDGE = 18

        // a row may be this much of the height it needs, so the rows can share the height of the grid
        const val MIN_ROW = 0.9f
        const val ANIM_MS = 200L
        const val WEATHER_MS = 30 * 60 * 1000L
        const val FOCUS_SCALE = 1.06f
        const val LIFT_SCALE = 1.15f
        const val MOVE_SCALE = 1.15f
    }
}
