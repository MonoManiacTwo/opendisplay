package app.opendisplay.android.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.opendisplay.android.R

/**
 * The iOS receiver's SettingsView as an Android sheet: same sections, same
 * order, same footers. A full-screen dialog rather than an Activity, so the
 * stream keeps running underneath, as the iOS sheet does.
 */
class SettingsSheet(
    context: Context,
    private val kind: String,
    private val state: State,
    private val onNameChanged: (String?) -> Unit,
    private val onOverlayChanged: (Boolean) -> Unit,
) : Dialog(context, themeFor(context)) {

    /** Live values for the Status section, polled while the sheet is open. */
    interface State {
        val connected: Boolean
        val streamSummary: String?
    }

    private val palette = Palette(context)
    private val prefs = AppPrefs(context)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var connectionValue: TextView
    private lateinit var streamRow: View
    private lateinit var streamValue: TextView
    private var pendingName: String? = null

    private val refresh = object : Runnable {
        override fun run() {
            connectionValue.text = context.getString(
                if (state.connected) R.string.row_connected else R.string.row_waiting)
            val stream = state.streamSummary
            streamRow.visibility = if (stream != null) View.VISIBLE else View.GONE
            streamValue.text = stream ?: ""
            ui.postDelayed(this, 1000)
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        root.addView(toolbar(), LinearLayout.LayoutParams(MATCH, dp(52)))
        // Open at the top like the iOS sheet: the name field must not take
        // initial focus and scroll the form (or raise the keyboard) by itself.
        val scroll = ScrollView(context).apply {
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        scroll.addView(form)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        buildForm(form)
        root.padToSafeArea()
        setContentView(root)
        window?.enterImmersive()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setOnShowListener {
            scroll.requestFocus()
            scroll.scrollTo(0, 0)
            ui.post(refresh)
        }
        setOnDismissListener {
            ui.removeCallbacks(refresh)
            commitName()
        }
    }

    private fun toolbar() = FrameLayout(context).apply {
        setBackgroundColor(palette.background)
        addView(TextView(context).apply {
            text = context.getString(R.string.title)
            setTextColor(palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        addView(TextView(context).apply {
            text = context.getString(R.string.done)
            setTextColor(palette.tint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { dismiss() }
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
    }

    private fun buildForm(form: LinearLayout) {
        section(form, R.string.section_status, null) { card ->
            card.addView(valueRow(R.string.row_listening, context.getString(R.string.row_listening_value)))
            card.addView(separator())
            val (row, value) = valueRowWithValue(R.string.row_connection)
            connectionValue = value
            card.addView(row)
            val (sRow, sValue) = valueRowWithValue(R.string.row_stream)
            streamValue = sValue
            // Only while a stream runs, separator included.
            streamRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(separator())
                addView(sRow)
            }
            card.addView(streamRow)
        }

        section(form, R.string.section_name, context.getString(R.string.name_footer)) { card ->
            card.addView(EditText(context).apply {
                setText(DeviceIdentity.serviceName(context))
                setSingleLine()
                setTextColor(palette.label)
                background = null
                imeOptions = EditorInfo.IME_ACTION_DONE
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        pendingName = s?.toString()
                    }
                })
                setOnEditorActionListener { _, _, _ ->
                    commitName()
                    clearFocus()
                    false
                }
            })
        }

        section(form, R.string.section_analytics, context.getString(R.string.analytics_footer)) { card ->
            card.addView(switchRow(R.string.performance_overlay, prefs.showAnalytics) {
                prefs.showAnalytics = it
                onOverlayChanged(it)
            })
        }

        section(form, R.string.section_diagnostics, context.getString(R.string.diagnostics_footer, kind)) { card ->
            card.addView(linkRow(R.string.connection_log) { LogSheet(context).show() })
        }

        section(form, R.string.section_how, null) { card ->
            listOf(
                context.getString(R.string.how_wifi, kind),
                context.getString(R.string.how_rotate, kind),
                context.getString(R.string.how_touch),
                context.getString(R.string.how_pen),
            ).forEachIndexed { i, text ->
                if (i > 0) card.addView(separator())
                card.addView(textRow(text))
            }
        }

        section(form, null, context.getString(R.string.mac_app_footer)) { card ->
            card.addView(linkRow(R.string.get_mac_app) { open(MAC_APP_URL) })
        }

        section(form, R.string.section_about, null) { card ->
            card.addView(valueRow(R.string.row_version, versionName()))
            card.addView(separator())
            card.addView(linkRow(R.string.link_website) { open("https://opendisplay.app") })
            card.addView(separator())
            card.addView(linkRow(R.string.link_github) { open("https://github.com/peetzweg/opendisplay") })
            card.addView(separator())
            card.addView(linkRow(R.string.link_kofi) { open("https://ko-fi.com/peetzweg") })
        }
    }

    private fun commitName() {
        val name = pendingName ?: return
        pendingName = null
        onNameChanged(name.trim().ifEmpty { null })
    }

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
    } catch (_: Exception) {
        "dev"
    }

    private fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // No browser installed: nothing sensible to do.
        }
    }

    // region Form building blocks (iOS grouped Form look)

    private fun section(form: LinearLayout, header: Int?, footer: String?, rows: (LinearLayout) -> Unit) {
        if (header != null) {
            form.addView(TextView(context).apply {
                text = context.getString(header).uppercase()
                setTextColor(palette.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(16), 0, dp(16), dp(6))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) })
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card, dp(10).toFloat())
        }
        rows(card)
        form.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            if (header == null) topMargin = dp(22)
        })
        if (footer != null) {
            form.addView(TextView(context).apply {
                text = footer
                setTextColor(palette.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(16), dp(6), dp(16), 0)
            })
        }
    }

    private fun valueRow(title: Int, value: String) = valueRowWithValue(title).also { it.second.text = value }.first

    private fun valueRowWithValue(title: Int): Pair<View, TextView> {
        val value = TextView(context).apply {
            setTextColor(palette.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.END
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(label(title), LinearLayout.LayoutParams(WRAP, WRAP))
            addView(value, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        }
        return row to value
    }

    private fun switchRow(title: Int, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), dp(4), dp(12), dp(4))
            addView(label(title), LinearLayout.LayoutParams(0, WRAP, 1f))
            val toggle = Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
            addView(toggle)
            setOnClickListener { toggle.toggle() }
        }

    private fun linkRow(title: Int, onClick: () -> Unit) = TextView(context).apply {
        text = context.getString(title)
        setTextColor(palette.tint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        minimumHeight = dp(44)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    private fun textRow(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(palette.label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16), dp(11), dp(16), dp(11))
    }

    private fun label(title: Int) = TextView(context).apply {
        text = context.getString(title)
        setTextColor(palette.label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    }

    private fun separator() = View(context).apply {
        setBackgroundColor(palette.separator)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { leftMargin = dp(16) }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // endregion

    companion object {
        const val MAC_APP_URL = "https://opendisplay.app/"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun themeFor(context: Context): Int =
            if (Palette(context).dark) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
    }
}
