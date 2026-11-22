package xyz.hidasy.pebblepinmanager.ui.theme

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

private val LightColorScheme = lightColorScheme(
    primary = PebbleCoral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = PebbleTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB5F0EF),
    onSecondaryContainer = Color(0xFF002020),
    background = SurfaceLight,
    surface = SurfaceLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = PebbleCoralDark,
    onPrimary = Color(0xFF5D1800),
    primaryContainer = Color(0xFF832500),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = PebbleTealLight,
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F4F),
    onSecondaryContainer = Color(0xFFB5F0EF),
    background = SurfaceDark,
    surface = SurfaceDark,
)

@Composable
fun PebblePinManagerTheme(
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PebblePinManagerTypography,
        content = content,
    )
}
