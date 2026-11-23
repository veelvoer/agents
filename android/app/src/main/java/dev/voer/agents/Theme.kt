package dev.voer.agents

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** What the user chose in Settings. Kept on this phone. */
data class Prefs(val mode: String = "system", val colors: String = "clay", val textScale: Float = 1f, val reduceMotion: Boolean = false, val autoUpdate: Boolean = true) {
    fun save(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        .putString("mode", mode).putString("colors", colors).putFloat("scale", textScale).putBoolean("reduce", reduceMotion).putBoolean("auto", autoUpdate).apply()
    companion object {
        fun load(ctx: Context): Prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).let {
            Prefs(it.getString("mode", "system")!!, it.getString("colors", "clay")!!, it.getFloat("scale", 1f), it.getBoolean("reduce", false), it.getBoolean("auto", true))
        }
    }
}

val LocalMotion = compositionLocalOf { true }
val dynamicColorsAvailable get() = Build.VERSION.SDK_INT >= 31

val Ok = Color(0xFF4CAF7D)
// Same palette as Hearth: warm paper, clay accent.
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

@Composable
fun AgentsTheme(p: Prefs, content: @Composable () -> Unit) {
    val dark = when (p.mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    // "System colours" = Material You: the phone's wallpaper colours. Older phones fall back to the clay colours.
    val scheme = if (p.colors == "system" && dynamicColorsAvailable) (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)).let {
        it.copy(background = it.surface, error = it.primary)
    } else if (dark) Dark else Light
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * p.textScale), LocalMotion provides !p.reduceMotion) {
        MaterialTheme(colorScheme = scheme, typography = Type, content = content)
    }
}

fun modelColor(id: String) = when { "opus" in id || "fable" in id -> Color(0xFFC15F3C); "sonnet" in id -> Color(0xFFD9A441); "haiku" in id -> Color(0xFF6A9BCC); else -> Color(0xFF9C9A92) }
