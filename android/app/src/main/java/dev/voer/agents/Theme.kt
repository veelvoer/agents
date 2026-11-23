package dev.voer.agents

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Same palette as Hearth: warm paper, clay accent.
val Ok = Color(0xFF4CAF7D)
private val Light = lightColorScheme(
    background = Color(0xFFF1EEE7), surface = Color(0xFFF1EEE7), surfaceContainer = Color(0xFFEEEAE0), surfaceContainerHigh = Color(0xFFFBFAF6),
    onBackground = Color(0xFF2F2B20), onSurface = Color(0xFF2F2B20), onSurfaceVariant = Color(0xFF6B665A),
    primary = Color(0xFFB04D2A), onPrimary = Color(0xFFFBFAF6), outline = Color(0xFF8F8A7B), outlineVariant = Color(0xFFE2DCCD),
    primaryContainer = Color(0xFFE6DDCB), onPrimaryContainer = Color(0xFF2F2B20), error = Color(0xFFB04D2A),
)
private val Dark = darkColorScheme(
    background = Color(0xFF1B1A19), surface = Color(0xFF1B1A19), surfaceContainer = Color(0xFF161514), surfaceContainerHigh = Color(0xFF252422),
    onBackground = Color(0xFFF4F2EC), onSurface = Color(0xFFF4F2EC), onSurfaceVariant = Color(0xFF9A968C),
    primary = Color(0xFFF0643C), onPrimary = Color(0xFF1B1A19), outline = Color(0xFF77736A), outlineVariant = Color(0xFF34322E),
    primaryContainer = Color(0xFF2E2A26), onPrimaryContainer = Color(0xFFF4F2EC), error = Color(0xFFF0643C),
)
private val Serif = FontFamily.Serif
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = Serif, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

@Composable fun AgentsTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)

fun modelColor(id: String) = when { "opus" in id || "fable" in id -> Color(0xFFC15F3C); "sonnet" in id -> Color(0xFFD9A441); "haiku" in id -> Color(0xFF6A9BCC); else -> Color(0xFF9C9A92) }
