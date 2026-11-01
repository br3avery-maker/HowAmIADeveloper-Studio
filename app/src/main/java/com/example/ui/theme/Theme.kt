package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val BreBreDarkColorScheme = darkColorScheme(
    primary = BreDeepPink,
    onPrimary = BreWhite,
    primaryContainer = BrePinkDark3,
    onPrimaryContainer = BreLightPink,
    secondary = BreLightPink,
    onSecondary = BreBlack,
    secondaryContainer = BrePinkDark1,
    onSecondaryContainer = BreWhite,
    tertiary = ZineBoneCream,
    onTertiary = BreBlack,
    tertiaryContainer = BreGray850,
    onTertiaryContainer = ZineBoneCream,
    error = CoralAlert,
    onError = BreWhite,
    errorContainer = CoralAlertContainer,
    onErrorContainer = BreLightPink,
    background = BreBlack,
    onBackground = BreWhite,
    surface = BreGray900,
    onSurface = BreWhite,
    surfaceVariant = BreGray850,
    onSurfaceVariant = BreGray100,
    outline = BrePinkDark2,
    outlineVariant = BreGray600
)

val SovereignShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BreBreDarkColorScheme,
        typography = Typography,
        shapes = SovereignShapes,
        content = content
    )
}
