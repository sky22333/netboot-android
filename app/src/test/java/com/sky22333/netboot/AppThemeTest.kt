package com.sky22333.netboot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppThemeTest {
    @Test fun `text and action colors remain readable in both themes`() {
        for (dark in listOf(false, true)) {
            val c = appColors(dark)
            val pairs = listOf(
                "body" to (c.onBackground to c.background),
                "card" to (c.onSurfaceContainer to c.surfaceContainer),
                "summary" to (c.onSurfaceVariantSummary to c.surfaceContainer),
                "page summary" to (c.onSurfaceVariantSummary to c.background),
                "primary button" to (c.onPrimary to c.primary),
                "secondary button" to (c.onSecondaryVariant to c.secondaryVariant),
                "selected" to (c.onPrimaryContainer to c.primaryContainer),
                "link" to (c.primary to c.surfaceContainer),
                "error" to (c.error to c.surfaceContainer),
                "error notice" to (c.onErrorContainer to c.errorContainer),
            )
            for ((role, pair) in pairs) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue(ratio >= 4.5f, "dark=$dark $role contrast=$ratio")
            }
        }
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
