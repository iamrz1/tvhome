package com.iamrz1.tvhome

import android.content.Context

enum class PillItem(val label: String) {
    CLOCK("Clock"),
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
    SETTINGS("Settings"),
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tvhome", Context.MODE_PRIVATE)

    var wallpaper: Int
        get() = sp.getInt(KEY_WALLPAPER, 0)
        set(v) = sp.edit().putInt(KEY_WALLPAPER, v).apply()

    var dock: List<String>
        get() = sp.getString(KEY_DOCK, null)?.split(',')?.filter { it.isNotEmpty() } ?: DEFAULT_DOCK
        set(v) = sp.edit().putString(KEY_DOCK, v.joinToString(",")).apply()

    var pill: Set<PillItem>
        get() = sp.getString(KEY_PILL, null)
            ?.split(',')
            ?.mapNotNull { n -> PillItem.entries.firstOrNull { it.name == n } }
            ?.toSet()
            ?: PillItem.entries.toSet()
        set(v) = sp.edit().putString(KEY_PILL, v.joinToString(",") { it.name }).apply()

    companion object {
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_DOCK = "dock"
        private const val KEY_PILL = "pill"

        val DEFAULT_DOCK = listOf(
            "com.netflix.ninja",
            "com.flixr.flixr",
            "com.stremio.one",
            "app.blaze.spofficial",
            "com.cricfytv.sports",
        )
    }
}
