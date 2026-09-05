package app.morpho.design

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

// Morpho-wing blue — the brand accent when dynamic color is unavailable.
private val MorphoBlue = Color(0xFF2458E6)
private val MorphoBlueBright = Color(0xFF7DA0FF)

private val LightColors = lightColorScheme(
    primary = MorphoBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF59627A),
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = MorphoBlueBright,
    onPrimary = Color(0xFF0D1B4A),
    primaryContainer = Color(0xFF14318F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF9AA5C0),
    onSecondary = Color(0xFF141A2C),
)

@Composable
fun MorphoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
