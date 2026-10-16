package app.opendisplay.android.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.opendisplay.android.R

/**
 * The iOS first-run OnboardingView: OpenDisplay is two apps, and this side is
 * useless without the Mac app. Shown until the first connection or Close.
 */
class OnboardingSheet(context: Context, kind: String, onClose: () -> Unit) :
    Dialog(context, SettingsSheet.themeFor(context)) {
    private val palette = Palette(context)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        val bar = FrameLayout(context)
        bar.addView(TextView(context).apply {
            text = context.getString(R.string.welcome)
            setTextColor(palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        bar.addView(TextView(context).apply {
            text = context.getString(R.string.close)
            setTextColor(palette.tint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { dismiss() }
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, dp(52)))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        column.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_laptop)
            imageTintList = ColorStateList.valueOf(palette.tint)
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(24) })
        column.addView(TextView(context).apply {
            text = context.getString(R.string.onboarding_title)
            setTextColor(palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        column.addView(TextView(context).apply {
            text = context.getString(R.string.onboarding_body, kind)
            setTextColor(palette.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10); bottomMargin = dp(28) })

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(6))
            background = rounded(palette.card, dp(16).toFloat())
        }
        listOf(R.string.onboarding_step1, R.string.onboarding_step2, R.string.hint_keep_open)
            .forEachIndexed { i, step -> card.addView(step(i + 1, context.getString(step))) }
        column.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))

        column.addView(Button(context).apply {
            text = context.getString(R.string.get_mac_app)
            isAllCaps = false
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(palette.tint, dp(12).toFloat())
            setOnClickListener {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsSheet.MAC_APP_URL)))
                } catch (_: Exception) {
                }
            }
        }, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(28) })
        column.addView(TextView(context).apply {
            text = context.getString(R.string.onboarding_footer, kind)
            setTextColor(palette.tertiary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(28) })

        val scroll = ScrollView(context)
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        window?.enterImmersive()
        window?.setLayout(MATCH, MATCH)
        setOnDismissListener { onClose() }
    }

    private fun step(n: Int, text: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            this.text = n.toString()
            setTextColor(palette.card)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(palette.label, dp(11).toFloat())
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply {
            this.text = text
            setTextColor(palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
