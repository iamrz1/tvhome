package com.iamrz1.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

class AppEntry(val pkg: String, val label: String, val info: ResolveInfo) {
    val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
}

object Apps {
    private val CATEGORIES = listOf(
        Intent.CATEGORY_LEANBACK_LAUNCHER,
        Intent.CATEGORY_LAUNCHER,
        "android.intent.category.LEANBACK_SETTINGS",
    )

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (category in CATEGORIES) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                out += AppEntry(pkg, ri.loadLabel(pm).toString(), ri)
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    fun launch(ctx: Context, app: AppEntry) {
        val intent = Intent.makeMainActivity(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(intent)
    }
}
