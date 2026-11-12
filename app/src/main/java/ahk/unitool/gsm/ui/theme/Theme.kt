package ahk.unitool.gsm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary80,
    onPrimary = CyanOnPrimary80,
    primaryContainer = CyanPrimaryContainer80,
    onPrimaryContainer = CyanOnPrimaryContainer80,
    secondary = VioletSecondary80,
    onSecondary = VioletOnSecondary80,
    secondaryContainer = VioletSecondaryContainer80,
    onSecondaryContainer = VioletOnSecondaryContainer80,
    tertiary = EmeraldTertiary80,
    onTertiary = EmeraldOnTertiary80,
    tertiaryContainer = EmeraldTertiaryContainer80,
    onTertiaryContainer = EmeraldOnTertiaryContainer80,
    background = DarkBackground,
    onBackground = Color(0xFFE2E2E9),
    surface = DarkSurface,
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    outline = Color(0xFF8E9099)
)

private val LightColorScheme = lightColorScheme(
    primary = CyanPrimary40,
    onPrimary = CyanOnPrimary40,
    primaryContainer = CyanPrimaryContainer40,
    onPrimaryContainer = CyanOnPrimaryContainer40,
    secondary = VioletSecondary40,
    onSecondary = VioletOnSecondary40,
    secondaryContainer = VioletSecondaryContainer40,
    onSecondaryContainer = VioletOnSecondaryContainer40,
    tertiary = EmeraldTertiary40,
    onTertiary = EmeraldOnTertiary40,
    tertiaryContainer = EmeraldTertiaryContainer40,
    onTertiaryContainer = EmeraldOnTertiaryContainer40,
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainer = Color(0xFFF0F4F8),
    surfaceContainerHigh = Color(0xFFE8EEF4),
    outline = Color(0xFF74777F)
)

@Composable
fun MyApplicationTheme(
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
