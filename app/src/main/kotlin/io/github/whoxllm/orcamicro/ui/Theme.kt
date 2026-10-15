package io.github.whoxllm.orcamicro.ui

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

private val Teal = Color(0xFF1F9E8A)
private val TealLight = Color(0xFF64D6C2)
private val Orange = Color(0xFFFF8A3D)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003730),
    secondary = Orange,
    background = Color(0xFF15181C),
    surface = Color(0xFF1B1F24),
    surfaceVariant = Color(0xFF262B32),
)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Color(0xFFB85A14),
)

@Composable
fun OrcaMicroTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
