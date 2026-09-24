package com.sky22333.netboot

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal fun appColors(dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).run {
    val accent = Color(if (dark) 0xFFC7B5A5 else 0xFF705D4E)
    val onAccent = Color(if (dark) 0xFF2E261F else 0xFFFFFFFF)
    val softAccent = Color(if (dark) 0xFF38312B else 0xFFEFE8E1)
    val background = Color(if (dark) 0xFF141413 else 0xFFF6F5F2)
    val surface = Color(if (dark) 0xFF222220 else 0xFFFFFFFF)
    val raised = Color(if (dark) 0xFF30302D else 0xFFEEEDE9)
    val text = Color(if (dark) 0xFFF2F1EC else 0xFF242422)
    val summary = Color(if (dark) 0xFFB6B4AC else 0xFF68665F)
    val disabled = Color(if (dark) 0xFF77766F else 0xFF99968E)
    copy(
        primary = accent, onPrimary = onAccent,
        primaryVariant = accent, onPrimaryVariant = onAccent,
        primaryContainer = softAccent, onPrimaryContainer = accent,
        disabledPrimary = raised, disabledOnPrimary = disabled,
        disabledPrimaryButton = raised, disabledOnPrimaryButton = disabled,
        disabledPrimarySlider = disabled,
        secondary = raised, onSecondary = text,
        secondaryVariant = raised, onSecondaryVariant = text,
        secondaryContainer = raised, onSecondaryContainer = summary,
        secondaryContainerVariant = raised, onSecondaryContainerVariant = summary,
        disabledSecondary = raised, disabledOnSecondary = disabled,
        disabledSecondaryVariant = raised, disabledOnSecondaryVariant = disabled,
        tertiaryContainer = softAccent, onTertiaryContainer = accent,
        tertiaryContainerVariant = softAccent,
        background = background, onBackground = text, onBackgroundVariant = summary,
        surface = background, onSurface = text, surfaceVariant = surface,
        onSurfaceSecondary = text, onSurfaceVariantSummary = summary,
        onSurfaceVariantActions = summary, disabledOnSurface = disabled,
        surfaceContainer = surface, onSurfaceContainer = text, onSurfaceContainerVariant = summary,
        surfaceContainerHigh = raised, onSurfaceContainerHigh = summary,
        surfaceContainerHighest = raised, onSurfaceContainerHighest = text,
        outline = Color(if (dark) 0xFF77766F else 0xFF8C8981),
        dividerLine = Color(if (dark) 0xFF383834 else 0xFFE4E2DC),
        error = Color(if (dark) 0xFFFFB4BC else 0xFFB11F3A),
        onError = Color(if (dark) 0xFF650020 else 0xFFFFFFFF),
        errorContainer = Color(if (dark) 0xFF3F1822 else 0xFFFFEBEF),
        onErrorContainer = Color(if (dark) 0xFFFFD9DF else 0xFF81132B),
        sliderKeyPoint = disabled, sliderKeyPointForeground = accent, sliderBackground = raised,
    )
}
