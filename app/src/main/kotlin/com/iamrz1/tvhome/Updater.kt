package com.iamrz1.tvhome

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Update(val version: String, val url: String)

object Updater {
    private const val RELEASES = "https://github.com/iamrz1/tvhome/releases"

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"

    /**
     * check returns the latest release if it is newer than the running app, else null.
     * It blocks and throws on network errors, so call it off the main thread.
     * It reads the redirect of `releases/latest` instead of the GitHub API, which limits unauthenticated calls per IP.
     */
    fun check(ctx: Context): Update? {
        val tag = latestTag()
        val latest = tag.removePrefix("v")
        if (!isNewer(latest, currentVersion(ctx))) return null
        val url = assetUrl(tag, latest, Build.SUPPORTED_ABIS)
            ?: throw IOException("No APK for ${Build.SUPPORTED_ABIS.joinToString()} in $tag")
        return Update(latest, url)
    }

    /** download saves the APK to [dest] and reports progress as a percentage, or -1 if the size is unknown. */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = connect(url, "GET", follow = true)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode} from $url")
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

    // releases/latest answers with a redirect to releases/tag/<tag>
    private fun latestTag(): String {
        val conn = connect("$RELEASES/latest", "GET", follow = false)
        try {
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            if (code !in 300..399 || location == null) throw IOException("HTTP $code from $RELEASES/latest")
            return location.substringAfterLast("/tag/").substringBefore('?').ifEmpty {
                throw IOException("No tag in redirect to $location")
            }
        } finally {
            conn.disconnect()
        }
    }

    // asset names keep the full ABI string (tvhome-<version>-arm64-v8a.apk), most preferred ABI first.
    private fun assetUrl(tag: String, version: String, abis: Array<String>): String? {
        for (abi in abis) {
            val url = "$RELEASES/download/$tag/tvhome-$version-$abi.apk"
            val conn = connect(url, "HEAD", follow = false)
            try {
                if (conn.responseCode in 300..399) return url
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun connect(url: String, method: String, follow: Boolean): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = follow
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "tvhome")
        return conn
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
