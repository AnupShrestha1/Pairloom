package com.anupshrestha.pairloom.ui.theme

import androidx.compose.ui.graphics.Color

// Pairloom's own palette ("two threads", docs/plans/phase6-ux.md). Teal is this phone's thread,
// saffron the other phone's; red is only for ending and errors.

// Dark theme: true black, so AMOLED screens switch those pixels off (and the neon rings stand out);
// surfaces are lifted just enough to separate buttons and cards.
val PitchBlack = Color(0xFF000000)
val NightSurface = Color(0xFF111316)
val NightRaised = Color(0xFF1B1E23)
val NightOutline = Color(0xFF34383F)
val Mist = Color(0xFF9EA5AF)
val NightText = Color(0xFFECEEF1)

val CoolPaper = Color(0xFFEEF1F5)
val PaperSurface = Color(0xFFF8FAFC)
val PaperRaised = Color(0xFFE1E7EF)
val PaperOutline = Color(0xFFC3CCDA)
val Slate = Color(0xFF4F5D74)
val PaperText = Color(0xFF162036)

/** This phone's thread. The darker shade carries white text in the light theme (contrast 5:1). */
val TealBright = Color(0xFF2BB3AC)
val Teal = Color(0xFF1E9C96)
val TealDeep = Color(0xFF157A75)
val TealInk = Color(0xFF00201F)

/** The other phone's thread. The darker shade is for text on light backgrounds. */
val Saffron = Color(0xFFF2A93B)
val SaffronDeep = Color(0xFF9A5C06)
val SaffronInk = Color(0xFF2A1B00)

val SignalRed = Color(0xFFC73A3A)
val SignalRedBright = Color(0xFFE5675F)
