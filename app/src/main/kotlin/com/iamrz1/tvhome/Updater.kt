package com.iamrz1.tvhome

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Update(val version: String, val url: String, val notes: String)

object Updater {
    private const val LATEST = "https://api.github.com/repos/iamrz1/tvhome/releases/latest"

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"

    /**
     * check returns the latest release if it is newer than the running app, else null.
     * It blocks and throws on network or parse errors, so call it off the main thread.
     */
    fun check(ctx: Context): Update? {
        val conn = connect(LATEST)
        val body = try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(body)
        val latest = json.optString("tag_name").removePrefix("v")
        if (latest.isEmpty() || !isNewer(latest, currentVersion(ctx))) return null
        val url = assetUrl(json, Build.SUPPORTED_ABIS)
            ?: throw IOException("No APK for ${Build.SUPPORTED_ABIS.joinToString()}")
        return Update(latest, url, json.optString("body"))
    }

    /** download saves the APK to [dest] and reports progress as a percentage, or -1 if the size is unknown. */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = connect(url)
        try {
            val total = conn.contentLengthLong
            var received = 0L
            var last = -2
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        val pct = if (total > 0) (received * 100 / total).toInt() else -1
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** install hands [apk] to the system installer. The user still confirms the prompt. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("tvhome.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // the installer adds its status extras to this intent, so it has to be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun connect(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "tvhome")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode} from $url")
        }
        return conn
    }

    // asset names keep the full ABI string (tvhome-<version>-arm64-v8a.apk), most preferred ABI first.
    private fun assetUrl(release: JSONObject, abis: Array<String>): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (abi in abis) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk") && name.contains(abi)) {
                    return asset.optString("browser_download_url").ifEmpty { null }
                }
            }
        }
        return null
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until 3) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }
}
