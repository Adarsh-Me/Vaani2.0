package com.itantra.walkie.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The console palette. Two committed hues - ink-blue-black for the panel ground and signal
 * green for transmission - plus amber as the only alert colour. Everything else is a tonal step
 * of the ground, so a row that is not talking is never competing with one that is.
 *
 * Dark is not a stylistic default here: a field crew uses this outdoors in daylight, at arm's
 * length, with a bright screen being the thing that survives. Light mode is deliberately not
 * offered rather than half-implemented.
 */
internal object VaniColors {
    val Ground = Color(0xFF080D13)
    val Panel = Color(0xFF101822)
    val PanelRaised = Color(0xFF16212D)
    val PanelLit = Color(0xFF1D2B39)
    val Rule = Color(0xFF243444)

    /** Transmit green: the on-air state and the sender's own bubbles. */
    val Signal = Color(0xFF2FBF71)
    val SignalDeep = Color(0xFF0E3B2A)
    val SignalEdge = Color(0xFF1C6E4B)

    /** Amber: a radio condition that needs attention, never a decoration. */
    val Alert = Color(0xFFE8A825)
    val AlertDeep = Color(0xFF3A2A08)

    val Ink = Color(0xFFE9F0F6)
    val InkDim = Color(0xFF93A6B6)

    /** The quietest grey on the panel, and still 4.5:1 against the raised surface. */
    val InkFaint = Color(0xFF8299AB)
    val OnSignal = Color(0xFF04170E)
    val Error = Color(0xFFE2685E)
}

/**
 * Two faces, both guaranteed on the platform.
 *
 * The console voice - labels, pilot words, readouts - is the fixed-pitch face, because that is
 * the voice of the machine reporting its own state: node counts, signal, ages, milliseconds. The
 * human voice - names, sentences, everything an operator wrote - is the platform sans. The
 * condensed cut a radio panel would traditionally be set in is deliberately not used: this
 * device ships only Roboto-Regular, and a face that falls back silently is a face the design
 * cannot rely on.
 */
internal val VaniConsoleFamily: FontFamily = FontFamily.Monospace
internal val VaniHumanFamily: FontFamily = FontFamily.SansSerif

private fun console(size: Int, weight: FontWeight = FontWeight.Medium, color: Color,
                    tracking: Float = 0f) = TextStyle(
    fontFamily = VaniConsoleFamily, fontSize = size.sp, fontWeight = weight,
    color = color, letterSpacing = tracking.sp, lineHeight = (size + 6).sp
)

private fun human(size: Int, weight: FontWeight = FontWeight.Normal, color: Color,
                  tracking: Float = 0f) = TextStyle(
    fontFamily = VaniHumanFamily, fontSize = size.sp, fontWeight = weight,
    color = color, letterSpacing = tracking.sp, lineHeight = (size + 7).sp
)

/**
 * Chat text is deliberately larger than a Material body default. The reading distance is a
 * gloved hand holding a phone at chest height, not a face-to-screen commute read.
 */
internal val VaniType = Typography(
    displaySmall = human(32, FontWeight.Bold, VaniColors.Ink, -0.4f),
    headlineMedium = human(23, FontWeight.Bold, VaniColors.Ink, -0.3f),
    headlineSmall = human(19, FontWeight.Bold, VaniColors.Ink, -0.2f),
    titleLarge = human(17, FontWeight.SemiBold, VaniColors.Ink, -0.1f),
    titleMedium = human(15, FontWeight.Medium, VaniColors.Ink),
    titleSmall = human(13, FontWeight.Medium, VaniColors.InkDim),
    bodyLarge = human(19, FontWeight.Normal, VaniColors.Ink),
    bodyMedium = human(16, FontWeight.Normal, VaniColors.Ink),
    bodySmall = human(14, FontWeight.Normal, VaniColors.InkDim),
    labelLarge = console(14, FontWeight.Bold, VaniColors.Ink, 1f),
    labelMedium = console(12, FontWeight.Medium, VaniColors.InkDim, 1f),
    labelSmall = console(11, FontWeight.Normal, VaniColors.InkFaint, 0.4f),
)

private val VaniDark: ColorScheme = darkColorScheme(
    primary = VaniColors.Signal,
    onPrimary = VaniColors.OnSignal,
    primaryContainer = VaniColors.SignalDeep,
    onPrimaryContainer = VaniColors.Ink,
    secondary = VaniColors.Alert,
    onSecondary = Color(0xFF171102),
    secondaryContainer = VaniColors.AlertDeep,
    onSecondaryContainer = VaniColors.Ink,
    background = VaniColors.Ground,
    onBackground = VaniColors.Ink,
    surface = VaniColors.Panel,
    onSurface = VaniColors.Ink,
    surfaceVariant = VaniColors.PanelRaised,
    onSurfaceVariant = VaniColors.InkDim,
    outline = VaniColors.Rule,
    outlineVariant = VaniColors.PanelLit,
    error = VaniColors.Error,
    onError = Color(0xFF1A0603),
    scrim = Color(0xE6000000),
)

/** Panel corner language: hardware panels are barely radiused; only bubbles get chat softness. */
internal val VaniRadius = 6.dp
internal val VaniRadiusChip = 4.dp
internal val VaniRadiusBubble = 14.dp

internal val VaniShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(VaniRadiusChip),
    small = androidx.compose.foundation.shape.RoundedCornerShape(VaniRadiusChip),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(VaniRadius),
    large = androidx.compose.foundation.shape.RoundedCornerShape(VaniRadius),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(VaniRadius),
)

/** The minimum anywhere a finger lands. Field use with gloves makes this a floor, not a goal. */
internal val VaniTarget = 48.dp

@Composable
fun VaniTheme(content: @Composable () -> Unit) {
    // The console has one scheme. isSystemInDarkTheme is consulted only so a light-forced
    // device still gets the dark panel it was designed against rather than a broken invert.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = VaniDark, typography = VaniType, shapes = VaniShapes, content = content)
}
