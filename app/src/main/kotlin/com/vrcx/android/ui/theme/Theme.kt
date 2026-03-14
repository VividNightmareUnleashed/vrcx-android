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
    shimmerBase = VrcxDarkSurfaceContainerHigh,
    shimmerHighlight = VrcxDarkSurfaceContainerHighest,
)

private val LightVrcxColors = VrcxColors(
    shimmerBase = VrcxLightSurfaceContainerHigh,
    shimmerHighlight = VrcxLightSurfaceContainerHighest,
)

@Composable
fun VrcxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
