package com.anupshrestha.pairloom.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The phone's own system font (Roboto, One UI Sans, MiSans...), so the app feels native and names
// in any script render properly. Personality comes from scale and weight: the other phone's name
// is the biggest thing on screen, then one status line, then small supporting text.
private val Base = Typography()

val Typography = Typography(
    // The other phone's name.
    displaySmall = Base.displaySmall.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp),
    // The pairing code and the call timer (tabular figures so digits do not jump).
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", letterSpacing = 4.sp),
    // The status line under the name.
    titleLarge = Base.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    titleMedium = Base.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

/** Figures that keep their width, for counters that change every second. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")
