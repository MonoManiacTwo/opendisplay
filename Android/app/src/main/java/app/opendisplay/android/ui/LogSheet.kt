package app.opendisplay.android.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.opendisplay.android.Log
import app.opendisplay.android.R

/** The iOS DiagnosticsLogView: the connection log, newest at the bottom, with Share. */
class LogSheet(context: Context) : Dialog(context, SettingsSheet.themeFor(context)) {
    private val palette = Palette(context)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        // Shared as text: no FileProvider (and no androidx) needed on API 21.
        // The tail is what matters for a GitHub issue and keeps the Intent small.
        val log = Log.read().takeLast(200_000)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        val bar = FrameLayout(context)
        bar.addView(barButton(R.string.share) {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "OpenDisplay connection log")
                putExtra(Intent.EXTRA_TEXT, log)
            }, null))
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
        bar.addView(TextView(context).apply {
            text = context.getString(R.string.connection_log)
            setTextColor(palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        bar.addView(barButton(R.string.done) { dismiss() },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, dp(52)))

        val scroll = ScrollView(context)
        scroll.addView(TextView(context).apply {
            text = log.ifEmpty { context.getString(R.string.log_empty) }
            setTextColor(palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(24))
        })
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        window?.enterImmersive()
        window?.setLayout(MATCH, MATCH)
        setOnShowListener { scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    }

    private fun barButton(title: Int, onClick: () -> Unit) = TextView(context).apply {
        text = context.getString(title)
        setTextColor(palette.tint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
