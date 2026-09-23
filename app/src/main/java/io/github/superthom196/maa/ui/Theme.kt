package io.github.superthom196.maa.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Fallback before Android 12: a calm blue close to Music Assistant's own accent. */
private val FallbackDark = darkColorScheme(
    primary = Color(0xFF9CCAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = Color(0xFFBBC7DB),
    tertiary = Color(0xFFD6BEE4),
)

/**
 * Always dark: the app is mostly glanced at in a car or a dark room, and it matches the Android
 * Auto UI it configures. Wallpaper colours on Android 12+.
 */
@Composable
fun MaaTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(LocalContext.current) else FallbackDark
    MaterialTheme(colorScheme = scheme, content = content)
}
