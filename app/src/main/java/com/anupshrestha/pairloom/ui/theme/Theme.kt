package com.anupshrestha.pairloom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Dynamic (wallpaper) colour is off on purpose: Pairloom looks the same on every phone, and its two
// thread colours mean something (this phone / the other phone).

private val DarkColors = darkColorScheme(
    primary = TealBright,
    onPrimary = TealInk,
    primaryContainer = IndigoRaised,
    onPrimaryContainer = NightText,
    secondary = Saffron,
    onSecondary = SaffronInk,
    background = IndigoNight,
    onBackground = NightText,
    surface = IndigoNight,
    onSurface = NightText,
    surfaceVariant = IndigoSurface,
    onSurfaceVariant = Mist,
    surfaceContainer = IndigoSurface,
    surfaceContainerHigh = IndigoRaised,
    outline = IndigoOutline,
    outlineVariant = IndigoOutline,
    error = SignalRedBright,
    onError = Color.White,
)

private val LightColors = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = PaperRaised,
    onPrimaryContainer = PaperText,
    secondary = SaffronDeep,
    onSecondary = Color.White,
    background = CoolPaper,
    onBackground = PaperText,
    surface = CoolPaper,
    onSurface = PaperText,
    surfaceVariant = PaperSurface,
    onSurfaceVariant = Slate,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperRaised,
    outline = PaperOutline,
    outlineVariant = PaperOutline,
    error = SignalRed,
    onError = Color.White,
)

/** The two thread colours and the hang-up red, which Material's scheme has no names for. */
data class ThreadColors(val self: Color, val peer: Color, val peerText: Color, val hangUp: Color, val onHangUp: Color)

val LocalThreads = staticCompositionLocalOf {
    ThreadColors(self = Teal, peer = Saffron, peerText = Saffron, hangUp = SignalRed, onHangUp = Color.White)
}

@Composable
fun PairloomTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val threads = if (darkTheme) {
        ThreadColors(self = TealBright, peer = Saffron, peerText = Saffron, hangUp = SignalRed, onHangUp = Color.White)
    } else {
        ThreadColors(self = Teal, peer = Saffron, peerText = SaffronDeep, hangUp = SignalRed, onHangUp = Color.White)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalThreads provides threads) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            content = content,
        )
    }
}
