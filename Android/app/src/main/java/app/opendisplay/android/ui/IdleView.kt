package app.opendisplay.android.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.opendisplay.android.R

/**
 * Shown while nothing streams; the iOS receiver's IdleView: logo, title,
 * status dot, a card of hints, "Settings & Help", and the shake tip.
 */
class IdleView(context: Context, kind: String, onSettings: () -> Unit) : ScrollView(context) {
    private val palette = Palette(context)
    private val dot = View(context)
    private val status = TextView(context)

    init {
        isFillViewport = true
        setBackgroundColor(palette.background)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        column.addView(spacer(), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(ImageView(context).apply { setImageResource(R.drawable.app_logo) },
            LinearLayout.LayoutParams(dp(132), dp(132)).apply { bottomMargin = dp(28) })
        column.addView(TextView(context).apply {
            text = context.getString(R.string.title)
            setTextColor(palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        statusRow.addView(status.apply {
            setTextColor(palette.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        column.addView(statusRow, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(6)
            bottomMargin = dp(28)
        })

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(6))
            background = rounded(palette.card, dp(16).toFloat())
        }
        card.addView(hint(R.drawable.ic_laptop, context.getString(R.string.hint_open_mac)))
        card.addView(hint(R.drawable.ic_wifi, context.getString(R.string.hint_pick_wifi, kind)))
        card.addView(hint(R.drawable.ic_play, context.getString(R.string.hint_keep_open)))
        column.addView(card, LinearLayout.LayoutParams(minOf(dp(420), screenWidth() - dp(32)), WRAP))

        column.addView(spacer(), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(Button(context).apply {
            text = context.getString(R.string.settings_and_help)
            isAllCaps = false
            setTextColor(palette.tint)
            background = rounded(palette.card, dp(10).toFloat())
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { onSettings() }
        }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { topMargin = dp(28) })
        column.addView(TextView(context).apply {
            text = context.getString(R.string.shake_tip, kind)
            setTextColor(palette.tertiary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14); bottomMargin = dp(8) })
    }

    fun setStatus(text: String, connected: Boolean) {
        status.text = text
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (connected) palette.green else palette.orange)
        }
    }

    private fun hint(icon: Int, text: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(palette.label)
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply {
            this.text = text
            setTextColor(palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP).apply {
            bottomMargin = dp(14)
        }
    }

    private fun spacer() = View(context)
    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}

internal fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}
