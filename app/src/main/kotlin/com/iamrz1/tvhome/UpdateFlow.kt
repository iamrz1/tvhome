package com.iamrz1.tvhome

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class UpdateFlow(private val activity: Activity, private val builder: () -> AlertDialog.Builder) {
    private val ui = Handler(Looper.getMainLooper())

    fun start() {
        toast("Checking for updates...")
        thread {
            val result = runCatching { Updater.check(activity) }
            ui.post {
                if (activity.isFinishing) return@post
                result
                    .onFailure { toast("Could not check for updates") }
                    .onSuccess { if (it == null) toast("You are on the latest version") else offer(it) }
            }
        }
    }

    private fun offer(update: Update) {
        val notes = update.notes.trim().take(500)
        builder()
            .setTitle("Update available")
            .setMessage("${Updater.currentVersion(activity)} -> ${update.version}\n\n$notes")
            .setPositiveButton("Install") { _, _ -> install(update) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun install(update: Update) {
        if (!Updater.canInstall(activity)) {
            builder()
                .setTitle("Allow installs")
                .setMessage("Turn on \"Allow from this source\" for TV Home, then check for updates again.")
                .setPositiveButton("Open settings") { _, _ -> Updater.openInstallSettings(activity) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val dialog = builder()
            .setTitle("Downloading ${update.version}")
            .setMessage("0%")
            .setCancelable(false)
            .show()
        thread {
            val result = runCatching {
                val apk = File(activity.cacheDir, "update.apk")
                Updater.download(update.url, apk) { pct ->
                    ui.post { dialog.setMessage(if (pct < 0) "..." else "$pct%") }
                }
                Updater.install(activity, apk)
            }
            ui.post {
                dialog.dismiss()
                result.onFailure { toast("Update failed: ${it.message}") }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
