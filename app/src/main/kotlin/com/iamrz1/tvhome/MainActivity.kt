package com.iamrz1.tvhome

import android.app.Activity
import android.app.AlertDialog
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
    private var apps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        palette = Palette.resolve(prefs.themeMode, resources.configuration)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        hideAllApps()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (prefs.themeMode == ThemeMode.AUTO) applyTheme()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (allAppsVisible()) hideAllApps()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            !allAppsVisible() && currentFocus?.parent === dockRow
        ) {
            showAllApps()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun builder() = AlertDialog.Builder(this, palette.dialogTheme)

    private fun applyTheme() {
        palette = Palette.resolve(prefs.themeMode, resources.configuration)
        pill.background = round(palette.pill, 30)
        dockScroll.background = round(palette.dock, 28)
        allApps.setBackgroundColor(palette.overlay)
        allTitle.setTextColor(palette.label)
        fillPill()
        rebuildDock()
        adapter.notifyDataSetChanged()
    }

    private fun ring(radiusDp: Int, oval: Boolean = false): Drawable {
        val active = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
            setColor(palette.focusFill)
            setStroke(dp(3), palette.focus)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), active)
            addState(intArrayOf(android.R.attr.state_selected), active)
            addState(intArrayOf(), GradientDrawable())
        }
    }

    private fun scaleTo(v: View, active: Boolean) {
        val s = if (active) 1.06f else 1f
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
        if (PillItem.BLUETOOTH in on) pill.addView(pillButton(R.drawable.ic_bluetooth, "Bluetooth") {
            open(Settings.ACTION_BLUETOOTH_SETTINGS)
        })
        if (PillItem.SETTINGS in on) pill.addView(pillButton(R.drawable.ic_settings, "Settings") {
            open(Settings.ACTION_SETTINGS)
        })
        pill.addView(pillButton(R.drawable.ic_tune, "Customize") { showCustomize() })
    }

    private fun pillButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(palette.muted)
        contentDescription = desc
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4); marginEnd = dp(4) }
        isFocusable = true
        foreground = ring(0, oval = true)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, f -> (v as ImageView).setColorFilter(if (f) palette.focus else palette.muted) }
    }

    private fun open(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
        }
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dockScroll.addView(dockRow)
        root.addView(
            dockScroll,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(dp(32), 0, dp(32), dp(32)) },
        )
    }

    private fun visibleDock(): List<String> {
        val installed = apps.mapTo(HashSet()) { it.pkg }
        return prefs.dock.filter { it in installed }
    }

    private fun rebuildDock(focusIndex: Int = dockRow.indexOfChild(currentFocus)) {
        val byPkg = apps.associateBy { it.pkg }
        dockRow.removeAllViews()
        for (pkg in visibleDock()) byPkg[pkg]?.let { dockRow.addView(dockTile(it)) }
        if (dockRow.childCount == 0) dockRow.addView(TextView(this).apply {
            text = "Press Down for all apps"
            setTextColor(palette.muted)
            textSize = 18f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        if (!allAppsVisible()) {
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
        tile.foreground = ring(14)
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

    private fun dockTile(app: AppEntry): View = newTile().apply {
        layoutParams = LinearLayout.LayoutParams(TILE_W, TILE_H).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        bindTile(this, app)
        isFocusable = true
        setOnClickListener { Apps.launch(context, app) }
        setOnLongClickListener { showDockMenu(app); true }
        setOnFocusChangeListener { v, f -> scaleTo(v, f) }
    }

    private fun showDockMenu(app: AppEntry) {
        val dock = visibleDock().toMutableList()
        val i = dock.indexOf(app.pkg)
        val options = arrayOf("Move left", "Move right", "Remove from dock")
        builder().setTitle(app.label).setItems(options) { _, which ->
            var focus = i
            when (which) {
                0 -> if (i > 0) { Collections.swap(dock, i, i - 1); focus = i - 1 }
                1 -> if (i < dock.size - 1) { Collections.swap(dock, i, i + 1); focus = i + 1 }
                2 -> dock.removeAt(i)
            }
            prefs.dock = dock
            rebuildDock(focus)
        }.show()
    }

    private fun buildAllApps() {
        grid = GridView(this).apply {
            numColumns = 5
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(4)
            selector = ColorDrawable(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> Apps.launch(this@MainActivity, apps[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showAppMenu(apps[pos]); true }
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

    private fun allAppsVisible() = allApps.visibility == View.VISIBLE

    private fun showAllApps() {
        pill.visibility = View.INVISIBLE
        dockScroll.visibility = View.INVISIBLE
        allApps.visibility = View.VISIBLE
        grid.setSelection(0)
        grid.requestFocus()
    }

    private fun hideAllApps() {
        if (!allAppsVisible()) return
        allApps.visibility = View.GONE
        pill.visibility = View.VISIBLE
        dockScroll.visibility = View.VISIBLE
        dockRow.getChildAt(0)?.requestFocus()
    }

    private fun showAppMenu(app: AppEntry) {
        val dock = visibleDock().toMutableList()
        val pinned = app.pkg in dock
        builder().setTitle(app.label)
            .setItems(arrayOf(if (pinned) "Remove from dock" else "Add to dock")) { _, _ ->
                if (pinned) dock.remove(app.pkg) else dock.add(app.pkg)
                prefs.dock = dock
                rebuildDock()
            }.show()
    }

    private fun showCustomize() {
        val options = arrayOf(
            "Dock apps",
            "Wallpaper",
            "Theme: ${prefs.themeMode.label}",
            "Top bar icons",
            "Reset dock",
            "Check for updates (v${Updater.currentVersion(this)})",
        )
        builder().setTitle("Customize").setItems(options) { _, which ->
            when (which) {
                0 -> pickDock()
                1 -> pickWallpaper()
                2 -> pickTheme()
                3 -> pickPill()
                4 -> { prefs.dock = Prefs.DEFAULT_DOCK; rebuildDock() }
                5 -> UpdateFlow(this, this::builder).start()
            }
        }.show()
    }

    private fun pickTheme() {
        val modes = ThemeMode.entries
        builder().setTitle("Theme")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), prefs.themeMode.ordinal) { d, which ->
                prefs.themeMode = modes[which]
                d.dismiss()
                applyTheme()
            }.show()
    }

    private fun pickDock() {
        val on = visibleDock().toMutableList()
        val checked = BooleanArray(apps.size) { apps[it].pkg in on }
        builder().setTitle("Dock apps")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                val pkg = apps[which].pkg
                if (isChecked) on += pkg else on -= pkg
            }
            .setPositiveButton("Done") { _, _ ->
                prefs.dock = on
                rebuildDock(0)
            }.show()
    }

    private fun pickWallpaper() {
        val names = Wallpapers.all.map { it.name }.toTypedArray()
        builder().setTitle("Wallpaper")
            .setSingleChoiceItems(names, prefs.wallpaper) { dialog, which ->
                prefs.wallpaper = which
                root.background = Wallpapers.get(which).drawable()
                dialog.dismiss()
            }.show()
    }

    private fun pickPill() {
        val items = PillItem.entries
        val on = prefs.pill.toMutableSet()
        val checked = BooleanArray(items.size) { items[it] in on }
        builder().setTitle("Top bar icons")
            .setMultiChoiceItems(items.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) on += items[which] else on -= items[which]
            }
            .setPositiveButton("Done") { _, _ ->
                prefs.pill = on
                fillPill()
            }.show()
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(MATCH_PARENT, TILE_H + dp(16))
                addView(newTile(), FrameLayout.LayoutParams(TILE_W, TILE_H, Gravity.CENTER))
            }
            bindTile(cell.getChildAt(0) as FrameLayout, apps[position])
            return cell
        }
    }

    private val TILE_W by lazy { dp(168) }
    private val TILE_H by lazy { dp(94) }
}
