package com.iamrz1.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
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
        palette = Palette.resolve(prefs.themeMode, resources.configuration)
        Panel.dark = palette.dark
        root = FrameLayout(this)
        root.background = Wallpapers.get(prefs.wallpaper).drawable()
        setContentView(root)
        buildPill()
        buildDock()
        buildAllApps()
    }

    override fun onResume() {
        super.onResume()
        apps = Apps.load(this)
        icons.clear()
        adapter.notifyDataSetChanged()
        rebuildDock()
    }

    override fun onPause() {
        super.onPause()
        endMove()
    }

    // Home on the home screen does nothing, except that it closes an open menu or the all apps screen
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Panel.dismiss()
        hideAllApps()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (prefs.themeMode == ThemeMode.AUTO) applyTheme()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
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
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun applyTheme() {
        palette = Palette.resolve(prefs.themeMode, resources.configuration)
        Panel.dark = palette.dark
        pill.background = round(palette.pill, 30)
        dockScroll.background = round(palette.dock, 28)
        allApps.setBackgroundColor(palette.overlay)
        allTitle.setTextColor(palette.label)
        fillPill()
        rebuildDock()
        adapter.notifyDataSetChanged()
    }

    private fun glowStyle() = prefs.focusStyle == FocusStyle.GLOW

    // what a focused top bar button shows: a ring, or a soft radial glow
    private fun pillMark(): Drawable {
        val active = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (glowStyle()) {
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
        val mark: Drawable = if (glowStyle()) {
            GlowDrawable(withAlpha(Color.WHITE, 0xC0), dp(14).toFloat(), dp(10).toFloat(), TILE_W, TILE_H)
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
        RingDrawable(color, dp(strokeDp).toFloat(), dp(2).toFloat(), dp(14).toFloat(), TILE_W, TILE_H)

    private fun scaleTo(v: View, active: Boolean, factor: Float = FOCUS_SCALE) {
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
        if (PillItem.WIFI in on) pill.addView(pillButton(R.drawable.ic_wifi, "Wi-Fi") {
            open(Settings.ACTION_WIFI_SETTINGS)
        })
        // Android TV settings has no Bluetooth settings screen. Its pairing screen answers CONNECT_INPUT instead.
        if (PillItem.BLUETOOTH in on) pill.addView(pillButton(R.drawable.ic_bluetooth, "Bluetooth") {
            open(Settings.ACTION_BLUETOOTH_SETTINGS, "com.google.android.intent.action.CONNECT_INPUT")
        })
        if (PillItem.SETTINGS in on) pill.addView(pillButton(R.drawable.ic_settings, "Settings") {
            open(Settings.ACTION_SETTINGS)
        })
        customizeButton = pillButton(R.drawable.ic_tune, "Customize") { showCustomize() }
        pill.addView(customizeButton)
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
            background = round(palette.dock, 28)
        }
        dockRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
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
        if (!allAppsOpen) {
            dockRow.getChildAt(focusIndex.coerceIn(0, dockRow.childCount - 1))?.requestFocus()
        }
    }

    private fun newTile() = FrameLayout(this).apply {
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun bindTile(tile: FrameLayout, app: AppEntry) {
        tile.removeAllViews()
        tile.background = round(palette.tile, 14)
        val banner = app.info.activityInfo.loadBanner(packageManager)
        if (banner != null) {
            tile.addView(ImageView(this).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
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
                scaleTo(cell, f, if (app.pkg == movingPkg) MOVE_SCALE else FOCUS_SCALE)
            }
        }
        cell.addView(tile, FrameLayout.LayoutParams(TILE_W, TILE_H, Gravity.CENTER))
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
        Panel.show(this, null, null, rows, anchor)
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
                    selectedCell?.let { scaleTo(it, false) }
                    selectedCell = v?.also { scaleTo(it, true) }
                }

                override fun onNothingSelected(p: AdapterView<*>) {
                    selectedCell?.let { scaleTo(it, false) }
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
        val rows = listOf(
            Row("Wallpaper") { pickWallpaper() },
            Row("Theme: ${prefs.themeMode.label}") { pickTheme() },
            Row("Focus highlight: ${prefs.focusStyle.label}") { pickFocusStyle() },
            Row("Top bar icons") { pickPill() },
            Row("Reset dock") {
                prefs.dock = Prefs.DEFAULT_DOCK
                rebuildDock()
            },
            Row("Check for updates (v${Updater.currentVersion(this)})") { UpdateFlow(this, customizeButton).start() },
        )
        Panel.show(this, "Customize", null, rows, customizeButton)
    }

    private fun pickWallpaper() {
        val rows = Wallpapers.all.mapIndexed { i, wallpaper ->
            Row(wallpaper.name, checked = i == prefs.wallpaper) {
                prefs.wallpaper = i
                root.background = Wallpapers.get(i).drawable()
            }
        }
        Panel.show(this, "Wallpaper", null, rows, customizeButton)
    }

    private fun pickTheme() {
        val rows = ThemeMode.entries.map { mode ->
            Row(mode.label, checked = mode == prefs.themeMode) {
                prefs.themeMode = mode
                applyTheme()
            }
        }
        Panel.show(this, "Theme", null, rows, customizeButton)
    }

    private fun pickFocusStyle() {
        val rows = FocusStyle.entries.map { style ->
            Row(style.label, checked = style == prefs.focusStyle) {
                prefs.focusStyle = style
                applyTheme()
            }
        }
        Panel.show(this, "Focus highlight", null, rows, customizeButton)
    }

    // each row applies at once, and Back closes the panel
    private fun pickPill() {
        val on = prefs.pill.toMutableSet()
        val rows = PillItem.entries.map { item ->
            Row(item.label, checked = item in on, keepOpen = true) { row ->
                if (item in on) on -= item else on += item
                row.checked = item in on
                prefs.pill = on
                fillPill()
            }
        }
        Panel.show(this, "Top bar icons", null, rows, customizeButton, onDismiss = { customizeButton?.requestFocus() })
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(MATCH_PARENT, TILE_H + 2 * ROOM)
                clipChildren = false
                addView(newTile(), FrameLayout.LayoutParams(TILE_W, TILE_H, Gravity.CENTER))
            }
            cell.background = cellBackground()
            bindTile(cell.getChildAt(0) as FrameLayout, apps[position])
            return cell
        }
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
        const val FOCUS_SCALE = 1.06f
        const val MOVE_SCALE = 1.12f
    }
}
