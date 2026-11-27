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
    private lateinit var pill: LinearLayout
    private lateinit var dockScroll: HorizontalScrollView
    private lateinit var dockRow: LinearLayout
    private lateinit var allApps: View
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
        root.background = Wallpapers.get(prefs.wallpaper).drawable()
        setContentView(root)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = Corners.radius(dp(radiusDp).toFloat())
    }

    private fun circle() = prefs.iconShape == IconShape.CIRCLE

    // the corner radius of a tile: none for a rectangle, and half the height for a circle, which is as wide as it is high
    private fun tileRadius() = when (prefs.iconShape) {
        IconShape.RECTANGLE -> 0f
        IconShape.ROUNDED -> dp(14).toFloat()
        IconShape.CIRCLE -> TILE_H / 2f
    }

    private fun tileWidth() = if (circle()) TILE_H else TILE_W

    // a circle shows the name under it all the time, and the lift style shows the name of the selected tile
    private fun namesBelow() = circle() || liftStyle()

    private fun nameShown(selected: Boolean) = circle() || (liftStyle() && selected)

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun applyTheme() {
        palette = Palette.resolve(prefs.themeMode, resources.configuration, prefs.dockOpacity)
        Panel.dark = palette.dark
        Corners.square = prefs.iconShape == IconShape.RECTANGLE
        pill.background = round(palette.pill, 30)
        dockScroll.background = dockBackground()
        allApps.setBackgroundColor(palette.overlay)
        allTitle.setTextColor(palette.label)
        fillPill()
        rebuildDock()
        // new cells, because the grid rows are taller when the focus style shows names
        grid.adapter = adapter
    }

    private fun glowStyle() = prefs.focusStyle == FocusStyle.GLOW

    private fun liftStyle() = prefs.focusStyle == FocusStyle.LIFT

    private fun focusScale() = if (liftStyle()) LIFT_SCALE else FOCUS_SCALE

    // the dock card has a thin edge, so it still reads as a card when it is clear
    private fun dockBackground() = GradientDrawable().apply {
        setColor(palette.dock)
        // the dock follows the icons when they are rectangles
        cornerRadius = Corners.radius(dp(28).toFloat())
        setStroke(dp(1), withAlpha(Color.WHITE, if (palette.dark) 0x26 else 0x99))
    }

    // what a focused top bar button shows: a ring, or a soft radial glow
    private fun pillMark(): Drawable {
        val active = GradientDrawable().apply {
            shape = if (Corners.square) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
            if (liftStyle()) {
                setColor(withAlpha(palette.focus, 0x33))
            } else if (glowStyle()) {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(23).toFloat()
                colors = intArrayOf(withAlpha(Color.WHITE, 0x59), withAlpha(Color.WHITE, 0x00))
            } else {
                setColor(palette.focusFill)
                setStroke(dp(3), palette.focus)
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), active)
            addState(intArrayOf(), GradientDrawable())
        }
    }

    // What a dock or grid cell draws behind its tile while the tile is focused (activated) or selected. Both styles
    // are drawn in the room around the tile, so the artwork is never covered and nothing is cut off.
    private fun cellBackground(): Drawable {
        val mark: Drawable = if (liftStyle()) {
            GlowDrawable(withAlpha(Color.BLACK, 0x80), tileRadius(), dp(8).toFloat(), tileWidth(), TILE_H, dp(3).toFloat())
        } else if (glowStyle()) {
            GlowDrawable(withAlpha(Color.WHITE, 0xC0), tileRadius(), dp(10).toFloat(), tileWidth(), TILE_H)
        } else {
            ringFrame(palette.focus, 3)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), mark)
            addState(intArrayOf(android.R.attr.state_selected), mark)
            addState(intArrayOf(), GradientDrawable())
        }
    }

    private fun ringFrame(color: Int, strokeDp: Int) =
        RingDrawable(color, dp(strokeDp).toFloat(), dp(2).toFloat(), tileRadius(), tileWidth(), TILE_H)

    private fun scaleTo(v: View, active: Boolean, factor: Float = focusScale()) {
        val s = if (active) factor else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
    }

    private fun icon(app: AppEntry): Drawable =
        icons.getOrPut(app.pkg) { app.info.loadIcon(packageManager) }

    private fun buildPill() {
        pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = round(palette.pill, 30)
        }
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
            textSize = 22f
            setTextColor(palette.muted)
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
        val icon = ImageView(this).apply { setColorFilter(palette.muted) }
        val temp = TextView(this).apply {
            textSize = 20f
            setTextColor(palette.muted)
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
        setColorFilter(palette.muted)
        contentDescription = desc
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4); marginEnd = dp(4) }
        isFocusable = true
        foreground = pillMark()
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, f -> (v as ImageView).setColorFilter(if (f) palette.focus else palette.muted) }
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
            background = dockBackground()
        }
        dockRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            // without this the row clips the scaled cells at its padding, which cuts the highlight at both ends
            clipToPadding = false
            // the end padding keeps the scaled highlight of the first and last cell inside the dock
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        dockScroll.addView(dockRow)
        root.addView(
            dockScroll,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(dp(20), 0, dp(20), dp(32)) },
        )
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

    private fun newTile() = FrameLayout(this).apply {
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun bindTile(tile: FrameLayout, app: AppEntry) {
        tile.removeAllViews()
        tile.background = GradientDrawable().apply {
            setColor(palette.tile)
            cornerRadius = tileRadius()
        }
        // a circle shows the app icon, because a wide banner does not fit in it
        val art = if (circle()) app.info.loadIcon(packageManager) else app.info.activityInfo.loadBanner(packageManager)
        if (art != null) {
            tile.addView(ImageView(this).apply {
                setImageDrawable(RoundedBanner(art, tileRadius(), fillCorners = !circle()))
                scaleType = ImageView.ScaleType.FIT_XY
            })
            return
        }
        tile.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(context).apply {
                    setImageDrawable(icon(app))
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                })
                addView(TextView(context).apply {
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
        val cell = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(TILE_W + 2 * ROOM, TILE_H + 2 * ROOM)
                .apply { setMargins(GAP / 2 - ROOM, 0, GAP / 2 - ROOM, 0) }
            clipChildren = false
            background = cellBackground()
        }
        val tile = newTile().apply {
            bindTile(this, app)
            isFocusable = true
            setOnClickListener { Apps.launch(context, app) }
            setOnLongClickListener { showAppMenu(app, it); true }
            setOnFocusChangeListener { _, f ->
                cell.isActivated = f
                scaleTo(cell, f, if (app.pkg == movingPkg) MOVE_SCALE else focusScale())
            }
        }
        cell.addView(tile, FrameLayout.LayoutParams(tileWidth(), TILE_H, Gravity.CENTER))
        return cell
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
        cell.background = ringFrame(Color.WHITE, 4)
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
        cell.background = cellBackground()
        scaleTo(cell, tileOf(cell)?.isFocused == true)
    }

    private fun buildAllApps() {
        grid = GridView(this).apply {
            numColumns = COLUMNS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(4)
            selector = ColorDrawable(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> Apps.launch(this@MainActivity, apps[pos]) }
            setOnItemLongClickListener { _, view, pos, _ -> showAppMenu(apps[pos], view); true }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                    selectedCell?.let { scaleTo(it, false); showName(it, false) }
                    selectedCell = v?.also { scaleTo(it, true); showName(it, true) }
                }

                override fun onNothingSelected(p: AdapterView<*>) {
                    selectedCell?.let { scaleTo(it, false); showName(it, false) }
                    selectedCell = null
                }
            }
        }
        allTitle = TextView(this).apply {
            text = "All apps"
            textSize = 24f
            setTextColor(palette.label)
            setPadding(0, 0, 0, dp(16))
        }
        allApps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // a selected cell is scaled up, so the grid must be free to draw outside its own bounds
            clipChildren = false
            setBackgroundColor(palette.overlay)
            setPadding(dp(32), dp(40), dp(32), dp(24))
            visibility = View.GONE
            addView(allTitle)
            addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        root.addView(allApps, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun showAllApps() {
        if (allAppsOpen) return
        allAppsOpen = true
        dockFocus = dockIndex(currentFocus).coerceAtLeast(0)
        setHomeFocusable(false)
        allApps.visibility = View.VISIBLE
        allApps.alpha = 0f
        allApps.translationY = SLIDE.toFloat()
        allApps.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator()).start()
        pill.animate().alpha(0f).setDuration(ANIM_MS).start()
        dockScroll.animate().alpha(0f).setDuration(ANIM_MS).start()
        grid.setSelection(0)
        grid.requestFocus()
    }

    private fun hideAllApps() {
        if (!allAppsOpen) return
        allAppsOpen = false
        setHomeFocusable(true)
        dockRow.getChildAt(dockFocus.coerceIn(0, dockRow.childCount - 1))?.requestFocus()
        allApps.animate().alpha(0f).translationY(SLIDE.toFloat()).setDuration(ANIM_MS)
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
            root.background = Wallpapers.get(prefs.wallpaper).drawable()
            applyTheme()
            refreshWeather()
        }

        override fun snapshot(): Bitmap {
            val scale = 0.5f
            val bitmap = Bitmap.createBitmap((root.width * scale).toInt(), (root.height * scale).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            root.draw(canvas)
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
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.05f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    visibility = View.INVISIBLE
                }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(4) })
            }
            cell.getChildAt(0).layoutParams = FrameLayout.LayoutParams(tileWidth(), TILE_H, Gravity.CENTER)
            cell.layoutParams = AbsListView.LayoutParams(MATCH_PARENT, TILE_H + 2 * ROOM + if (namesBelow()) dp(32) else 0)
            cell.background = cellBackground()
            bindTile(cell.getChildAt(0) as FrameLayout, apps[position])
            (cell.getChildAt(1) as TextView).apply {
                text = apps[position].label
                setTextColor(palette.label)
                visibility = if (nameShown(cell.isSelected)) View.VISIBLE else View.INVISIBLE
            }
            return cell
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
    private val SLIDE by lazy { dp(40) }

    private companion object {
        // the dock fits this many tiles on one screen
        const val MAX_DOCK = 5
        const val COLUMNS = 5
        const val ANIM_MS = 200L
        const val WEATHER_MS = 30 * 60 * 1000L
        const val FOCUS_SCALE = 1.06f
        const val LIFT_SCALE = 1.15f
        const val MOVE_SCALE = 1.15f
    }
}
