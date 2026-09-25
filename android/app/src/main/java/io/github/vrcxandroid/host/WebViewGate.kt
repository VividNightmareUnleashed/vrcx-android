package io.github.vrcxandroid.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.vrcxandroid.R

/** Startup gate: WebView ≥ 120 with document-start scripts and web message listeners (docs/ARCHITECTURE.md §6.3). */
object WebViewGate {
    private const val WEBVIEW_PACKAGE = "com.google.android.webview"

    data class Result(val ok: Boolean, val versionName: String?)

    fun check(context: Context): Result {
        val version = try {
            WebViewCompat.getCurrentWebViewPackage(context)?.versionName
        } catch (t: Throwable) {
            null
        }
        val documentStart = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        } catch (t: Throwable) {
            false
        }
        val messageListener = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        } catch (t: Throwable) {
            false
        }
        return Result(WebViewVersion.isSupported(version, documentStart, messageListener), version)
    }

    /** Simple native explanation screen with "Update WebView" and "Try again". */
    fun view(activity: Activity, result: Result): View {
        val fg = ContextCompat.getColor(activity, R.color.vrcx_on_frame)
        val muted = ContextCompat.getColor(activity, R.color.vrcx_muted)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        column.addView(TextView(activity).apply {
            text = activity.getString(R.string.webview_gate_title)
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        column.addView(TextView(activity).apply {
            val installed = result.versionName ?: activity.getString(R.string.webview_gate_missing)
            text = activity.getString(R.string.webview_gate_body, WebViewVersion.MIN_MAJOR, installed)
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(16), 0, dp(24))
        })
        column.addView(Button(activity).apply {
            text = activity.getString(R.string.webview_gate_update)
            setOnClickListener { openStore(activity) }
        })
        column.addView(Button(activity).apply {
            text = activity.getString(R.string.webview_gate_retry)
            setOnClickListener { activity.recreate() }
        })
        val scroll = ScrollView(activity).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return scroll
    }

    private fun openStore(activity: Activity) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$WEBVIEW_PACKAGE"))
        try {
            activity.startActivity(market)
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$WEBVIEW_PACKAGE")))
            } catch (_: Exception) {
            }
        }
    }
}
