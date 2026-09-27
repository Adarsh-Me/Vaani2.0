package com.itantra.walkie.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.itantra.walkie.R

/**
 * The VANI design system, ported from the handed-off `vani-app.css`. Every value below is the
 * OKLCH token from `brand-spec.md` converted to sRGB, not an approximation chosen by eye - and no
 * colour exists here that the stylesheet does not have a name for.
 *
 * The rules the palette is built to obey, in the design's own words: near-black ground that is
 * never pure, hairline borders, no shadow heavier than `0 1px 3px`, **one accent used at most twice
 * per screen**, and every status carried by a word plus a shape - never colour alone, because this
 * is read outdoors in daylight, with gloves. Selection is therefore `Ink`, not a second hue, and
 * the single extra tone (`Alert`, a dim gold) belongs to pending and unverified states only.
 *
 * Dark is not a stylistic default: a field crew uses this outside. Light mode is deliberately not
 * offered rather than half-implemented.
 */
internal object VaniColors {
    /** --bg oklch(15.5% .006 250) - page/screen ground. */
    val Ground = Color(0xFF0A0C0F)
    /** --surface oklch(21% .006 250) - cards, bubbles, bars. */
    val Panel = Color(0xFF16191B)
    /** --raised oklch(24% .006 250) - the floating nav, the avatar disc. */
    val PanelRaised = Color(0xFF1D2022)
    /** color-mix(--fg 10%, --surface) - an aria-pressed row or chip. */
    val PanelLit = Color(0xFF2C2E30)
    /** color-mix(--fg 18%, --surface) - hover. */
    val PanelHover = Color(0xFF3D4041)
    /** --border oklch(31% .008 250) - the hairline. */
    val Rule = Color(0xFF2D3134)

    /** --accent oklch(78% .17 152): the one mint. Live transmitter, pressed PTT, primary CTA. */
    val Signal = Color(0xFF4FD57F)
    /** color-mix(--accent 16%, --surface): the sender's own bubble ground. */
    val SignalDeep = Color(0xFF1F372B)
    /** --accent at 50% - the active pipeline stage's border. */
    val SignalEdge = Color(0x804FD57F)
    /** --accent at 34% - a sent bubble's border. */
    val SignalBorder = Color(0x574FD57F)
    /** --accent at 15% - the only soft fill in the system. */
    val SignalWash = Color(0x264FD57F)
    /** --accent-ink oklch(16% .02 152): text and glyphs that sit on mint. */
    val OnSignal = Color(0xFF071009)

    /** --warn oklch(72% .12 85): pending / not sent. Always beside a word, never alone. */
    val Alert = Color(0xFFC79E41)
    val AlertDeep = Color(0xFF2A2417)
    val AlertEdge = Color(0x73C79E41)

    /** --fg oklch(95.5% .004 95). */
    val Ink = Color(0xFFF1F0ED)
    /** --muted oklch(66% .008 95). */
    val InkDim = Color(0xFF94928D)
    /** --muted at 75%: the quietest label in the system, and still 5.6:1 on the ground. */
    val InkFaint = Color(0xBF94928D)
    /** --fg at 6%: a hairline where a border would shout. */
    val InkGhost = Color(0x0FF1F0ED)
    /** --focus-ring: --accent at 60%. */
    val FocusRing = Color(0x994FD57F)

    /**
     * Failures wear `--warn` too. The export has exactly two non-neutrals plus one state tone, and
     * a fourth hue for "the engine did not load" would break the one-accent rule for the rarest
     * string on the screen - so the word does that work instead.
     */
    val Error = Alert
}

/**
 * The three faces the design ships: Sora for display, Manrope for body and UI, JetBrains Mono for
 * every label, number and measured value - the voice of the machine reporting its own state.
 *
 * None of them carry Indic glyphs, and that is deliberate: the stack in `vani-app.css` names Noto
 * after each of them, and Android's system fallback resolves exactly that way, so a language name
 * renders in its own script while its Latin companion stays in the brand face. Nothing here relies
 * on a face the platform cannot draw.
 */
internal val VaniDisplayFamily = FontFamily(
    Font(R.font.sora_regular, FontWeight.Normal),
    Font(R.font.sora_bold, FontWeight.Bold),
)
internal val VaniHumanFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_bold, FontWeight.Bold),
)
internal val VaniConsoleFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** The `.cap` / label voice of the design system: mono, small, letterspaced, uppercase on request. */
private fun mono(size: Int, weight: FontWeight = FontWeight.Normal, color: Color,
                 tracking: Float = 0f) = TextStyle(
    fontFamily = VaniConsoleFamily, fontSize = size.sp, fontWeight = weight, color = color,
    letterSpacing = tracking.sp, lineHeight = (size + 5).sp,
)

private fun body(size: Int, weight: FontWeight = FontWeight.Normal, color: Color,
                 tracking: Float = 0f, lh: Float = 1.5f) = TextStyle(
    fontFamily = VaniHumanFamily, fontSize = size.sp, fontWeight = weight, color = color,
    letterSpacing = tracking.sp, lineHeight = (size * lh).sp,
)

/**
 * The type scale, taken from the stylesheet rule by rule rather than from a Material preset:
 * `.app-top h1` is 20/700 at -0.025em, `.result` is 16/1.45, `.group > h2` is 11/700 mono at
 * 0.1em uppercase, `.tabbar a` is 9 mono at 0.08em uppercase, and body copy is 15 - the size the
 * phone screen itself declares.
 */
internal val VaniType = Typography(
    displaySmall = body(28, FontWeight.Bold, VaniColors.Ink, -0.6f, 1.15f),
    headlineMedium = body(20, FontWeight.Bold, VaniColors.Ink, -0.5f, 1.1f),   // .app-top h1, h1
    headlineSmall = body(17, FontWeight.Bold, VaniColors.Ink, -0.3f, 1.2f),
    titleLarge = body(15, FontWeight.Medium, VaniColors.Ink, -0.1f),           // .peer .name
    titleMedium = body(16, FontWeight.Medium, VaniColors.Ink, -0.1f),          // .lang-opt .native
    titleSmall = body(12, FontWeight.Normal, VaniColors.InkDim),               // .app-top .sub
    bodyLarge = body(16, FontWeight.Normal, VaniColors.Ink, -0.16f, 1.45f),    // .result, .input
    bodyMedium = body(15, FontWeight.Normal, VaniColors.Ink),                  // the screen default
    bodySmall = body(13, FontWeight.Normal, VaniColors.InkDim),                // .notice
    labelLarge = mono(11, FontWeight.Bold, VaniColors.InkDim, 1.1f),           // .group > h2
    labelMedium = mono(11, FontWeight.Normal, VaniColors.InkDim, 0.33f),       // .chip, .meta
    labelSmall = mono(10, FontWeight.Normal, VaniColors.InkFaint, 0.3f),       // .cap
)

/** Text styles the export spells inline rather than in the scale. */
internal object VaniLabel {
    /** `.who`, `.stage`, `.ptt` - 10-11 mono at 0.08em, uppercase. */
    val eyebrow = mono(10, FontWeight.Normal, VaniColors.InkDim, 0.8f)
    val stage = mono(10, FontWeight.Normal, VaniColors.InkDim, 0.4f)
    val tab = mono(9, FontWeight.Normal, VaniColors.InkDim, 0.72f)
    val tone = mono(10, FontWeight.Normal, VaniColors.InkDim, 0.3f)
    val arrow = mono(10, FontWeight.Normal, VaniColors.InkDim, 1f)
    val badge = mono(9, FontWeight.Normal, VaniColors.InkDim, 0.55f)
    /** A measured value, mono at reading size: the handset meter rows. Tabular by family. */
    val readout = mono(17, FontWeight.Bold, VaniColors.Ink, -0.4f)
    val button = body(14, FontWeight.Medium, VaniColors.Ink, -0.07f)
    val buttonStrong = body(14, FontWeight.Bold, VaniColors.OnSignal, -0.07f)
}

/**
 * Corner language: `--radius` 12 for controls and inputs, `--radius-lg` 16 for cards, bubbles and
 * the nav (rounded-2xl), `--radius-xl` 24 for anything that floats. The old 4-6dp hardware radius
 * is gone - the design's panels are soft, not machined.
 */
internal val VaniRadius = 12.dp
/** `.chip`, `.replay`, the send button - the only true pills in the design. */
internal val VaniRadiusPill = 999.dp
/** Controls and inputs take `--radius`; nothing is 4dp machined any more. */
internal val VaniRadiusChip = 12.dp
internal val VaniRadiusBubble = 16.dp
internal val VaniTarget = 48.dp

internal val VaniShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(VaniRadius),
    medium = RoundedCornerShape(VaniRadius),
    large = RoundedCornerShape(VaniRadiusBubble),
    extraLarge = RoundedCornerShape(24.dp),
)

private val VaniDark: ColorScheme = darkColorScheme(
    primary = VaniColors.Signal,
    onPrimary = VaniColors.OnSignal,
    primaryContainer = VaniColors.SignalDeep,
    onPrimaryContainer = VaniColors.Ink,
    secondary = VaniColors.Alert,
    onSecondary = VaniColors.Ground,
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
    onError = VaniColors.Ground,
    scrim = Color(0xE6000000),
)

@Composable
fun VaniTheme(content: @Composable () -> Unit) {
    // One scheme. isSystemInDarkTheme is consulted only so a light-forced device still gets the
    // dark panel the design was drawn against rather than a broken invert.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = VaniDark, typography = VaniType, shapes = VaniShapes, content = content)
}
