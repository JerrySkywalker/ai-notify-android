package io.github.jerryskywalker.ainotify.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalPendingColor = staticCompositionLocalOf { Color(0xff805100) }

private data class Palette(
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onContainer: Color,
)

private fun palette(accent: Accent, dark: Boolean): Palette = if (dark) {
    when (accent) {
        Accent.JADE -> Palette(Color(0xff43c8a5), Color(0xff00382f), Color(0xff174f46), Color(0xffa1f5e3))
        Accent.BLUE -> Palette(Color(0xffa8c7ff), Color(0xff0a305f), Color(0xff214777), Color(0xffd6e3ff))
        Accent.VIOLET -> Palette(Color(0xffd0bbff), Color(0xff35275e), Color(0xff4d3d77), Color(0xffeadfff))
    }
} else {
    when (accent) {
        Accent.JADE -> Palette(Color(0xff106257), Color.White, Color(0xffb1edde), Color(0xff053831))
        Accent.BLUE -> Palette(Color(0xff2c5d9c), Color.White, Color(0xffd6e3ff), Color(0xff0a305f))
        Accent.VIOLET -> Palette(Color(0xff66538f), Color.White, Color(0xffeadfff), Color(0xff35275e))
    }
}

@Composable
internal fun PhoneTheme(prefs: PhonePreferences, setLightBars: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val dark = when (prefs.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.GRAPHITE, ThemeMode.AMOLED -> true
    }
    val amoled = prefs.theme == ThemeMode.AMOLED
    val colors = palette(prefs.accent, dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary,
            primaryContainer = colors.container, onPrimaryContainer = colors.onContainer,
            secondary = colors.primary, onSecondary = colors.onPrimary,
            secondaryContainer = colors.container, onSecondaryContainer = colors.onContainer,
            background = if (amoled) Color.Black else Color(0xff10161a),
            surface = if (amoled) Color.Black else Color(0xff10161a),
            surfaceContainerLowest = if (amoled) Color.Black else Color(0xff111514),
            surfaceContainerLow = if (amoled) Color(0xff0c0d0d) else Color(0xff192228),
            surfaceContainer = if (amoled) Color(0xff101212) else Color(0xff212725),
            surfaceContainerHigh = if (amoled) Color(0xff171a19) else Color(0xff29302e),
            surfaceContainerHighest = if (amoled) Color(0xff212524) else Color(0xff333b37),
            onSurface = Color(0xffe8eeeb), onSurfaceVariant = Color(0xffc4cdc8),
            outline = Color(0xff8b9792), outlineVariant = Color(0xff414b46),
        )
    } else {
        lightColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary,
            primaryContainer = colors.container, onPrimaryContainer = colors.onContainer,
            secondary = colors.primary, onSecondary = colors.onPrimary,
            secondaryContainer = colors.container, onSecondaryContainer = colors.onContainer,
            background = Color(0xfff5faf7), surface = Color(0xfff5faf7),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xffeff5f1),
            surfaceContainer = Color(0xffe8f0eb), surfaceContainerHigh = Color(0xffe0eae4),
            surfaceContainerHighest = Color(0xffd9e6df), onSurface = Color(0xff17231f),
            onSurfaceVariant = Color(0xff44534d), outline = Color(0xff6a7d74),
            outlineVariant = Color(0xffc3d4cc),
        )
    }
    SideEffect { setLightBars(!dark) }
    CompositionLocalProvider(LocalPendingColor provides if (dark) Color(0xfff4be62) else Color(0xff805100)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
