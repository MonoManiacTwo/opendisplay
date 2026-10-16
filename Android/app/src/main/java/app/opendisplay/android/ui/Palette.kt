package app.opendisplay.android.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * The iOS receiver's idle and settings screens follow light/dark mode
 * (systemBackground, secondarySystemBackground, label colors); these are
 * the same roles. The stream itself always sits on black.
 */
class Palette(context: Context) {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val background = if (dark) Color.BLACK else Color.rgb(242, 242, 247)
    val card = if (dark) Color.rgb(28, 28, 30) else Color.WHITE
    val label = if (dark) Color.WHITE else Color.BLACK
    val secondary = if (dark) Color.argb(153, 235, 235, 245) else Color.argb(153, 60, 60, 67)
    val tertiary = if (dark) Color.argb(77, 235, 235, 245) else Color.argb(77, 60, 60, 67)
    val separator = if (dark) Color.argb(90, 84, 84, 88) else Color.argb(74, 60, 60, 67)
    val tint = if (dark) Color.rgb(10, 132, 255) else Color.rgb(0, 122, 255)
    val green = Color.rgb(52, 199, 89)
    val orange = Color.rgb(255, 149, 0)
}
