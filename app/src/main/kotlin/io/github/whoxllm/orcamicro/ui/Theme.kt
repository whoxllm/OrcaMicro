package io.github.whoxllm.orcamicro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.whoxllm.orcamicro.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Accent and neutral colors from the pinned desktop OrcaSlicer GUI_App.cpp / official logo.
private val LightColors = lightColorScheme(
    primary = Color(0xFF009688), onPrimary = Color.White, primaryContainer = Color(0xFFD4EFEB), onPrimaryContainer = Color(0xFF004D45),
    secondary = Color(0xFF00796B), secondaryContainer = Color(0xFFD4EFEB), onSecondaryContainer = Color(0xFF004D45),
    background = Color(0xFFF1F1F1), surface = Color.White, surfaceVariant = Color(0xFFE9E9E9),
    onSurface = Color(0xFF262523), onSurfaceVariant = Color(0xFF626262), outlineVariant = Color(0xFFD7D7D7),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF35BCAE), onPrimary = Color(0xFF002F2A), primaryContainer = Color(0xFF005C53), onPrimaryContainer = Color(0xFFD0F4EE),
    secondary = Color(0xFF35BCAE), secondaryContainer = Color(0xFF005C53), onSecondaryContainer = Color(0xFFD0F4EE),
    background = Color(0xFF242424), surface = Color(0xFF2B2B2B), surfaceVariant = Color(0xFF36363B),
    onSurface = Color(0xFFFAFAFA), onSurfaceVariant = Color(0xFFB2B3B5), outlineVariant = Color(0xFF4A4A4A),
)
private val Inter = FontFamily(Font(R.font.inter, FontWeight.Normal), Font(R.font.inter, FontWeight.Medium), Font(R.font.inter, FontWeight.SemiBold))
private val CompactType = Typography(
    titleLarge = TextStyle(fontFamily = Inter, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 17.sp),
)
@Composable
fun OrcaMicroTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while(context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = CompactType,
        shapes = Shapes(RoundedCornerShape(4.dp), RoundedCornerShape(6.dp), RoundedCornerShape(8.dp), RoundedCornerShape(10.dp), RoundedCornerShape(12.dp)),
        content = content,
    )
}
