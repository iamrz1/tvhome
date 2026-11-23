package com.iamrz1.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Projector holds the shortcuts of the A6 Pro projector, which runs HTC LuminaOS. Its settings and its HDMI input
 * belong to apps that only that device has, so each shortcut is offered only when its app is installed.
 */
object Projector {
    private const val LUMINA = "com.htc.luminaos"
    private const val LIVE_TV = "com.softwinner.awlivetv"

    // The settings screen only opens after the main screen of LuminaOS has started, and that screen shows for a moment.
    // An overlay hides it. It needs the permission to draw over other apps, which also allows the second start.
    private const val SETTINGS_DELAY_MS = 2500L
    private const val OVERLAY_MS = 3600L

    private const val NO_ANIMATION = Intent.FLAG_ACTIVITY_NO_ANIMATION

    fun hasSettings(ctx: Context) = installed(ctx, LUMINA)

    fun hasHdmi(ctx: Context) = installed(ctx, LIVE_TV)

    private fun installed(ctx: Context, pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    fun openHdmi(ctx: Context) {
        val intent = component(LIVE_TV, "$LIVE_TV.MainActivity")
            .putExtra("input_source", "HDMI1")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            toast(ctx, "Cannot open HDMI: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun openSettings(ctx: Context) {
        val app = ctx.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            toast(app, "Allow TV Home to display over other apps, then try again")
            try {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                // no screen for it on this device, so the toast has to do
            }
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val overlay = showOverlay(app)
        try {
            app.startActivity(
                component(LUMINA, "$LUMINA.activity.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or NO_ANIMATION),
            )
        } catch (e: Exception) {
            hideOverlay(app, overlay)
            toast(app, "Cannot open projector settings: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        handler.postDelayed({
            try {
                app.startActivity(
                    component(LUMINA, "$LUMINA.activity.MainSettingActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or NO_ANIMATION),
                )
            } catch (e: Exception) {
                toast(app, "Cannot open projector settings: ${e.message ?: e.javaClass.simpleName}")
            }
        }, SETTINGS_DELAY_MS)
        handler.postDelayed({ hideOverlay(app, overlay) }, OVERLAY_MS)
    }

    private fun showOverlay(app: Context): View? {
        val view = FrameLayout(app).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            addView(
                TextView(app).apply {
                    text = "Opening projector settings..."
                    setTextColor(Color.parseColor("#B0B0B0"))
                    textSize = 22f
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        return try {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
            view
        } catch (e: Exception) {
            null
        }
    }

    private fun hideOverlay(app: Context, view: View?) {
        view ?: return
        try {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            // already gone
        }
    }

    private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
}
