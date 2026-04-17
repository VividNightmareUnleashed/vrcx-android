package com.vrcx.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = VrcxDarkPrimary,
    onPrimary = VrcxDarkOnPrimary,
    primaryContainer = VrcxDarkPrimaryContainer,
    onPrimaryContainer = VrcxDarkOnPrimaryContainer,
    secondary = VrcxDarkSecondary,
    onSecondary = VrcxDarkOnSecondary,
    secondaryContainer = VrcxDarkSecondaryContainer,
    onSecondaryContainer = VrcxDarkOnSecondaryContainer,
    tertiary = VrcxDarkTertiary,
    onTertiary = VrcxDarkOnTertiary,
    tertiaryContainer = VrcxDarkTertiaryContainer,
    onTertiaryContainer = VrcxDarkOnTertiaryContainer,
    background = VrcxDarkBackground,
    onBackground = VrcxDarkOnBackground,
    surface = VrcxDarkSurface,
    onSurface = VrcxDarkOnSurface,
    surfaceVariant = VrcxDarkSurfaceVariant,
    onSurfaceVariant = VrcxDarkOnSurfaceVariant,
    surfaceContainerLowest = VrcxDarkSurfaceContainerLowest,
    surfaceContainerLow = VrcxDarkSurfaceContainerLow,
    surfaceContainer = VrcxDarkSurfaceContainer,
    surfaceContainerHigh = VrcxDarkSurfaceContainerHigh,
    surfaceContainerHighest = VrcxDarkSurfaceContainerHighest,
    outline = VrcxDarkOutline,
    outlineVariant = VrcxDarkOutlineVariant,
    error = VrcxDarkError,
    onError = VrcxDarkOnError,
    errorContainer = VrcxDarkErrorContainer,
    onErrorContainer = VrcxDarkOnErrorContainer,
    inverseSurface = VrcxDarkInverseSurface,
    inverseOnSurface = VrcxDarkInverseOnSurface,
    inversePrimary = VrcxDarkInversePrimary,
)

private val LightColorScheme = lightColorScheme(
    primary = VrcxLightPrimary,
    onPrimary = VrcxLightOnPrimary,
    primaryContainer = VrcxLightPrimaryContainer,
    onPrimaryContainer = VrcxLightOnPrimaryContainer,
    secondary = VrcxLightSecondary,
    onSecondary = VrcxLightOnSecondary,
    secondaryContainer = VrcxLightSecondaryContainer,
    onSecondaryContainer = VrcxLightOnSecondaryContainer,
    tertiary = VrcxLightTertiary,
    onTertiary = VrcxLightOnTertiary,
    tertiaryContainer = VrcxLightTertiaryContainer,
    onTertiaryContainer = VrcxLightOnTertiaryContainer,
    background = VrcxLightBackground,
    onBackground = VrcxLightOnBackground,
    surface = VrcxLightSurface,
    onSurface = VrcxLightOnSurface,
    surfaceVariant = VrcxLightSurfaceVariant,
    onSurfaceVariant = VrcxLightOnSurfaceVariant,
    surfaceContainerLowest = VrcxLightSurfaceContainerLowest,
    surfaceContainerLow = VrcxLightSurfaceContainerLow,
    surfaceContainer = VrcxLightSurfaceContainer,
    surfaceContainerHigh = VrcxLightSurfaceContainerHigh,
    surfaceContainerHighest = VrcxLightSurfaceContainerHighest,
    outline = VrcxLightOutline,
    outlineVariant = VrcxLightOutlineVariant,
    error = VrcxLightError,
    onError = VrcxLightOnError,
    errorContainer = VrcxLightErrorContainer,
    onErrorContainer = VrcxLightOnErrorContainer,
    inverseSurface = VrcxLightInverseSurface,
    inverseOnSurface = VrcxLightInverseOnSurface,
    inversePrimary = VrcxLightInversePrimary,
)

private val DarkVrcxColors = VrcxColors(
    shellGradientStart = Color(0xFF0A0A0A),
    shellGradientEnd = Color(0xFF0A0A0A),
    panelBackground = Color(0xFF0A0A0A),
    panelElevated = Color(0xFF0A0A0A),
    panelHover = Color(0xFF262626),
    panelBorder = Color(0xFF262626),
    panelMuted = Color(0xFFA1A1A1),
    fieldBackground = Color(0xFF262626),
    focusRing = VrcxDarkRing,
    navActive = Color(0xFF262626),
    navActiveContent = Color(0xFFFAFAFA),
    navInactiveContent = Color(0xFFA1A1A1),
    shimmerBase = Color(0xFF262626),
    shimmerHighlight = Color(0xFF404040),
)

private val LightVrcxColors = VrcxColors(
    shellGradientStart = Color(0xFFF2F5F9),
    shellGradientEnd = Color(0xFFE8EDF5),
    panelBackground = Color(0xFFFAFBFD),
    panelElevated = Color(0xFFF0F4F9),
    panelHover = Color(0xFFE9EEF5),
    panelBorder = Color(0xFFD7DEE7),
    panelMuted = Color(0xFF556071),
    fieldBackground = Color(0xFFF4F7FB),
    focusRing = VrcxLightRing,
    navActive = VrcxLightPrimary.copy(alpha = 0.10f),
    navActiveContent = VrcxLightPrimary,
    navInactiveContent = Color(0xFF556071),
    shimmerBase = VrcxLightSurfaceContainerHigh,
    shimmerHighlight = VrcxLightSurfaceContainerHighest,
)

@Composable
fun VrcxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val vrcxColors = if (darkTheme) DarkVrcxColors else LightVrcxColors

    CompositionLocalProvider(LocalVrcxColors provides vrcxColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = VrcxShapes,
            content = content,
        )
    }
}
