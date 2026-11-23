package com.iamrz1.tvhome

import android.content.Context

enum class PillItem(val label: String) {
    CLOCK("Clock"),
    WEATHER("Weather"),
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
}

enum class FocusStyle(val label: String) {
    LINE("Line"),
    GLOW("Glow"),
    LIFT("Lift"),
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tvhome", Context.MODE_PRIVATE)

    var wallpaper: Int
        get() = sp.getInt(KEY_WALLPAPER, Wallpapers.DEFAULT)
        set(v) = sp.edit().putInt(KEY_WALLPAPER, v).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.entries.getOrElse(sp.getInt(KEY_THEME, ThemeMode.LIGHT.ordinal)) { ThemeMode.LIGHT }
        set(v) = sp.edit().putInt(KEY_THEME, v.ordinal).apply()

    var focusStyle: FocusStyle
        get() = FocusStyle.entries.getOrElse(sp.getInt(KEY_FOCUS, FocusStyle.LIFT.ordinal)) { FocusStyle.LIFT }
        set(v) = sp.edit().putInt(KEY_FOCUS, v.ordinal).apply()

    /** How opaque the dock card is, from 0 to 100 percent. */
    var dockOpacity: Int
        get() = sp.getInt(KEY_DOCK_OPACITY, DEFAULT_DOCK_OPACITY).coerceIn(0, 100)
        set(v) = sp.edit().putInt(KEY_DOCK_OPACITY, v).apply()

    var weatherPlace: Place?
        get() = sp.getString(KEY_PLACE, null)?.let(Place::parse)
        set(v) = sp.edit().putString(KEY_PLACE, v?.key).apply()

    var fahrenheit: Boolean
        get() = sp.getBoolean(KEY_FAHRENHEIT, false)
        set(v) = sp.edit().putBoolean(KEY_FAHRENHEIT, v).apply()

    /** The last weather that was read. It belongs to the place in its key, so it is ignored after a new pick. */
    var forecast: Forecast?
        get() {
            val key = sp.getString(KEY_FORECAST_PLACE, null) ?: return null
            return Forecast(key, sp.getFloat(KEY_FORECAST_C, 0f).toDouble(), sp.getInt(KEY_FORECAST_CODE, 0), sp.getLong(KEY_FORECAST_AT, 0))
        }
        set(v) {
            sp.edit().apply {
                if (v == null) {
                    remove(KEY_FORECAST_PLACE)
                } else {
                    putString(KEY_FORECAST_PLACE, v.place)
                    putFloat(KEY_FORECAST_C, v.celsius.toFloat())
                    putInt(KEY_FORECAST_CODE, v.code)
                    putLong(KEY_FORECAST_AT, v.at)
                }
            }.apply()
        }

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
        private const val KEY_THEME = "theme"
        private const val KEY_FOCUS = "focus"
        private const val KEY_DOCK = "dock"
        private const val KEY_DOCK_OPACITY = "dock_opacity"
        private const val KEY_PILL = "pill"
        private const val KEY_PLACE = "weather_place"
        private const val KEY_FAHRENHEIT = "fahrenheit"
        private const val KEY_FORECAST_PLACE = "forecast_place"
        private const val KEY_FORECAST_C = "forecast_c"
        private const val KEY_FORECAST_CODE = "forecast_code"
        private const val KEY_FORECAST_AT = "forecast_at"

        const val DEFAULT_DOCK_OPACITY = 40

        val DEFAULT_DOCK = listOf(
            "com.netflix.ninja",
            "com.flixr.flixr",
            "com.stremio.one",
            "app.blaze.spofficial",
            "com.cricfytv.sports",
        )
    }
}
