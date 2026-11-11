package com.iamrz1.tvhome

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class UpdateFlow(private val activity: Activity, private val anchor: View?) {
    private val ui = Handler(Looper.getMainLooper())

    fun start() {
        toast("Checking for updates...")
        thread {
            val result = runCatching { Updater.check(activity) }
            ui.post {
                if (activity.isFinishing) return@post
                result
                    .onFailure { fail("Could not check for updates", it) }
                    .onSuccess { if (it == null) toast("You are on the latest version") else offer(it) }
            }
        }
    }

    private fun offer(update: Update) {
        Panel.show(
            activity,
            "Update available",
            "${Updater.currentVersion(activity)} -> ${update.version}",
            listOf(Row("Install") { install(update) }, Row("Later") {}),
            anchor,
        )
    }

    private fun install(update: Update) {
        if (!Updater.canInstall(activity)) {
            Panel.show(
                activity,
                "Allow installs",
                "Turn on \"Allow from this source\" for TV Home, then check for updates again.",
                listOf(Row("Open settings") { Updater.openInstallSettings(activity) }, Row("Cancel") {}),
                anchor,
            )
            return
        }
        val panel = Panel.show(activity, "Downloading ${update.version}", "0%", emptyList(), anchor, cancelable = false)
        thread {
            val result = runCatching {
                val apk = File(activity.cacheDir, "update.apk")
                Updater.download(update.url, apk) { pct ->
                    ui.post { panel.setMessage(if (pct < 0) "..." else "$pct%") }
                }
                Updater.install(activity, apk)
            }
            ui.post {
                panel.dismiss()
                result.onFailure { fail("Update failed", it) }
            }
        }
    }

    // shows the reason too, so a failure can be told apart from the toast alone
    private fun fail(what: String, error: Throwable) {
        Log.e(TAG, what, error)
        toast("$what: ${error.javaClass.simpleName}: ${error.message}", Toast.LENGTH_LONG)
    }

    private fun toast(text: String, length: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(activity, text, length).show()

    private companion object {
        const val TAG = "tvhome"
    }
}
