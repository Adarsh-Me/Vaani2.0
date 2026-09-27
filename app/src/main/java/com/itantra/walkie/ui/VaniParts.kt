package com.itantra.walkie.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.walkie.Lang
import com.itantra.walkie.net.RadioState
import kotlinx.coroutines.delay

/**
 * The component library for the design in `vani-app.css`. Each composable here is one class from
 * that stylesheet - `.chip`, `.notice`, `.card`, `.group`, `.lang-opt`, `.switch-row`, `.peer`,
 * `.stage`, `.scan-line`, `.replay`, `.avatar`, `.btn`, `.input`, `.tabbar` - so a pane cannot
 * invent its own card and the screens stay on one system.
 */

/** `.hairline` - the only separator inside a card. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = VaniColors.Rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** `.card` - surface, hairline border, 16dp radius, 16dp padding. No shadow, no gradient. */
@Composable
fun VaniCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = PaddingValues16,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(VaniColors.Panel, RoundedCornerShape(VaniRadiusBubble))
            .border(1.dp, VaniColors.Rule, RoundedCornerShape(VaniRadiusBubble))
            .padding(contentPadding)
    ) { content() }
}

private val PaddingValues16 = androidx.compose.foundation.layout.PaddingValues(16.dp)

/** `.group > h2` + its content: a mono eyebrow that names what follows, then the block. */
@Composable
fun Group(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(top = 22.dp)) {
        Text(
            title.uppercase(),
            style = VaniType.labelLarge,
            color = VaniColors.InkDim,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        content()
    }
}

/**
 * `.chip` - a status a thumb can read at arm's length: icon, WORD, hairline ring. The word is not
 * optional; the design forbids a state that rides on colour alone.
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ChipTone = ChipTone.Plain,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(VaniRadiusPill)
    val (color, border) = when (tone) {
        ChipTone.Plain -> VaniColors.InkDim to VaniColors.Rule
        ChipTone.On -> VaniColors.Ink to VaniColors.Ink.copy(alpha = 0.34f)
        ChipTone.Live -> VaniColors.Signal to VaniColors.Signal
        ChipTone.Warn -> VaniColors.Alert to VaniColors.AlertEdge
    }
    Row(
        modifier
            .background(Color.Transparent, shape)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, style = VaniType.labelMedium, color = color, maxLines = 1)
    }
}

enum class ChipTone { Plain, On, Live, Warn }

/**
 * `.notice` - the design's honesty box: a bordered card whose bold clause is the fact and whose
 * muted clause is the consequence. `warn` adds the gold border for the unverified, never alone.
 */
@Composable
fun Notice(
    body: androidx.compose.ui.text.AnnotatedString,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    val shape = RoundedCornerShape(VaniRadiusBubble)
    Row(
        modifier
            .fillMaxWidth()
            .background(VaniColors.Panel, shape)
            .border(1.dp, if (warn) VaniColors.AlertEdge else VaniColors.Rule, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            if (warn) Icons.Outlined.Warning else Icons.Outlined.Info,
            null,
            tint = if (warn) VaniColors.Alert else VaniColors.InkDim,
            modifier = Modifier.size(16.dp).padding(top = 2.dp)
        )
        Text(body, style = VaniType.bodySmall, color = VaniColors.InkDim, maxLines = 8)
    }
}

@Composable
fun Notice(text: String, modifier: Modifier = Modifier, warn: Boolean = false) =
    Notice(
        androidx.compose.ui.text.buildAnnotatedString { append(text) }, modifier, warn
    )

/** `.avatar` - initials in mono on the raised disc. */
@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        modifier.size(size).background(VaniColors.PanelRaised, CircleShape)
            .border(1.dp, VaniColors.Rule, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            style = VaniConsoleStyle(size.value.toInt() / 3),
            color = VaniColors.Ink,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun VaniConsoleStyle(px: Int) = TextStyle(
    fontFamily = VaniConsoleFamily, fontSize = px.sp, letterSpacing = 0.3.sp
)

/**
 * `.lang-opt` - the language name in its own script (a brand commitment), the Latin transliteration
 * under it in mono, and a capability badge that says what this phone can actually do with it.
 * Selected wears `--fg`, not the accent: the accent budget is spent on the transmitter.
 */
@Composable
fun LangOption(
    native: String,
    latin: String,
    badge: String,
    badgeIcon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(VaniRadius)
    Column(
        modifier
            .defaultMinSize(minHeight = VaniTarget)
            .background(
                when {
                    selected -> VaniColors.PanelLit
                    !enabled -> VaniColors.Panel.copy(alpha = 0.6f)
                    else -> VaniColors.Panel
                },
                shape
            )
            .border(
                1.dp,
                if (selected) VaniColors.Ink else VaniColors.Rule,
                shape
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                native, style = VaniType.titleMedium,
                color = if (enabled) VaniColors.Ink else VaniColors.InkFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.align(Alignment.Top)
            ) {
                Icon(badgeIcon, null, tint = VaniColors.InkDim, modifier = Modifier.size(12.dp))
                Text(badge, style = VaniLabel.badge, color = VaniColors.InkDim, maxLines = 1)
            }
        }
        Text(latin, style = VaniType.labelSmall, color = VaniColors.InkDim, maxLines = 1)
    }
}

/**
 * `.switch-row` with `.switch` drawn to the design rather than borrowed from Material: a 46x28
 * track that fills with `--fg` (not the accent) and a 20dp knob that inverts to the ground.
 */
@Composable
fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier.fillMaxWidth()
            .clickable(role = Role.Checkbox) { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = VaniType.bodyMedium, color = VaniColors.Ink)
            Text(desc, style = VaniType.labelSmall, color = VaniColors.InkDim)
        }
        // The 46x28 pill with a generous invisible hit pad, so the row is the target.
        Box(
            Modifier.width(46.dp).height(28.dp)
                .background(
                    if (checked) VaniColors.Ink else VaniColors.Rule,
                    RoundedCornerShape(VaniRadiusPill)
                )
                .border(1.dp, VaniColors.Rule.copy(alpha = 0.7f), RoundedCornerShape(VaniRadiusPill)),
            contentAlignment = Alignment.CenterStart
        ) {
            val shift by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (checked) 1f else 0f,
                animationSpec = tween(150), label = "knob"
            )
            Box(
                Modifier.padding(start = (3 + shift * 18).dp).size(20.dp)
                    .background(if (checked) VaniColors.Ground else VaniColors.Ink, CircleShape)
            )
        }
    }
}

/** `.input` - ground-filled, hairline, 16sp so no platform zooms it on focus. */
@Composable
fun VaniField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLength: Int = 0,
) {
    val shape = RoundedCornerShape(VaniRadius)
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(if (maxLength <= 0) it else it.take(maxLength)) },
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(VaniColors.Ground, shape)
            .border(1.dp, VaniColors.Rule, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        textStyle = VaniType.bodyLarge.copy(color = VaniColors.Ink),
        cursorBrush = SolidColor(VaniColors.Signal),
        singleLine = singleLine,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty())
                    Text(
                        placeholder, style = VaniType.bodyLarge,
                        color = VaniColors.InkFaint
                    )
                inner()
            }
        }
    )
}

/** `.btn-primary` - the one mint button on a screen. Its label is ink on green, never white. */
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(VaniRadius)
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .background(if (enabled) VaniColors.Signal else VaniColors.PanelLit, shape)
            .border(1.dp, if (enabled) VaniColors.Signal else VaniColors.Rule, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) VaniColors.OnSignal else VaniColors.InkFaint,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = if (enabled) VaniLabel.buttonStrong else VaniLabel.button,
            color = if (enabled) VaniColors.OnSignal else VaniColors.InkFaint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** `.btn-ghost` - an action that must not claim the accent. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    minHeight: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val shape = RoundedCornerShape(VaniRadius)
    Row(
        modifier.defaultMinSize(minHeight = minHeight).background(Color.Transparent, shape)
            .border(1.dp, VaniColors.Rule, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = VaniColors.InkDim, modifier = Modifier.size(11.dp))
        Text(label, style = VaniType.labelMedium, color = VaniColors.InkDim, maxLines = 1)
    }
}

/** `.replay[aria-pressed]` - the same pill, brighter when this line is the one being heard. */
@Composable
fun ReplayPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(VaniRadiusPill)
    Row(
        modifier.defaultMinSize(minHeight = 44.dp)
            .background(if (active) VaniColors.PanelRaised else Color.Transparent, shape)
            .border(1.dp, if (active) VaniColors.Ink else VaniColors.Rule, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = if (active) VaniColors.Ink else VaniColors.InkDim,
            modifier = Modifier.size(11.dp))
        Text(
            label, style = VaniType.labelMedium.copy(letterSpacing = 0.55.sp),
            color = if (active) VaniColors.Ink else VaniColors.InkDim,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** `.stage` - one leg of the pipeline, with the dots that say which leg is running. */
@Composable
fun RowScope.Stage(label: String, state: StageState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(VaniRadius)
    val color = when (state) {
        StageState.Waiting -> VaniColors.InkDim
        StageState.Active -> VaniColors.Signal
        StageState.Done -> VaniColors.Ink
    }
    Row(
        modifier.weight(1f).background(VaniColors.Panel, shape)
            .border(
                1.dp,
                when (state) {
                    StageState.Active -> VaniColors.SignalEdge
                    StageState.Done -> VaniColors.Ink.copy(alpha = 0.4f)
                    StageState.Waiting -> VaniColors.Rule
                },
                shape
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (state) {
            StageState.Done -> Icon(Icons.Outlined.Check, null, tint = color, modifier = Modifier.size(12.dp))
            StageState.Active -> Box(Modifier.size(6.dp).background(color, CircleShape))
            StageState.Waiting -> Box(Modifier.size(6.dp).border(1.dp, VaniColors.Rule, CircleShape))
        }
        Text(
            label.uppercase(),
            style = VaniLabel.stage.copy(letterSpacing = 0.4.sp),
            color = color, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

enum class StageState { Waiting, Active, Done }

/** `.pipeline` - Listen / Translate / Speak, the machine naming its own stages. */
@Composable
fun Pipeline(visible: Boolean, stages: Triple<StageState, StageState, StageState>) {
    if (!visible) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Stage("Listen · STT", stages.first)
        Stage("Translate · MT", stages.second)
        Stage("Speak · TTS", stages.third)
    }
}

/** `.scan-line` - the dashed frame with the accent sweeping through it. */
@Composable
fun ScanLine(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(VaniRadius)
    val transition = rememberInfiniteTransition(label = "sweep")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    Box(
        modifier.fillMaxWidth().height(48.dp).drawBehind {
            drawRoundRect(
                color = VaniColors.Rule,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(VaniRadius.toPx(), VaniRadius.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                    )
                )
            )
            // One soft band crossing the frame, not a hard block: the design's sweep is a gradient
            // that enters from the left and leaves to the right.
            val w = size.width
            val band = w * 0.34f
            val left = -band + (w + band) * phase
            // The gradient has to be positioned on the band itself, or the clipped slice of a
            // full-width fade reads as a flat block sliding across the frame.
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, VaniColors.SignalWash, Color.Transparent),
                    startX = left, endX = left + band,
                ),
                topLeft = Offset(left, 0f),
                size = Size(band, size.height),
            )
        },
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = VaniType.labelMedium, color = VaniColors.InkDim, maxLines = 1)
    }
}

/**
 * `.peer` - one handset in range. Initials, name, what it speaks and how it was reached, and the
 * signal read as bars *and* |dBm| *and* a word, because a number alone is not field-legible.
 */
@Composable
fun PeerRow(
    initials: String,
    name: String,
    meta: String,
    rssi: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(VaniRadiusBubble)
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
            .background(if (selected) VaniColors.PanelLit else VaniColors.Panel, shape)
            .border(1.dp, if (selected) VaniColors.Ink else VaniColors.Rule, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(initials)
        Column(Modifier.weight(1f)) {
            Text(name, style = VaniType.titleLarge, color = VaniColors.Ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(meta, style = VaniType.labelMedium, color = VaniColors.InkDim, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            SignalBars(signalOf(rssi).bars)
            Text(
                "${kotlin.math.abs(rssi)}",
                style = VaniType.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                color = if (selected) VaniColors.Ink else VaniColors.InkDim
            )
            Text("dBm · ${signalOf(rssi).word}", style = VaniType.labelSmall, color = VaniColors.InkFaint)
        }
    }
}

/** `.signal(rssi)` from the design's own thresholds, with the word beside the bars. */
fun signalOf(rssi: Int): Signal = when {
    rssi > -65 -> Signal(3, "strong")
    rssi > -80 -> Signal(2, "ok")
    else -> Signal(1, "faint")
}
data class Signal(val bars: Int, val word: String)

/**
 * The design's three bars, drawn not glyphed. Empty steps stay visible at low alpha so a faint
 * peer reads as far away rather than as a missing icon.
 */
@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier, tint: Color = VaniColors.InkDim) {
    Canvas(modifier = modifier.size(14.dp, 11.dp)) {
        val slot = size.width / 3f
        val bw = slot * 0.62f
        for (i in 0 until 3) {
            val h = size.height * (0.45f + 0.275f * i)
            drawRect(
                color = if (i < bars) tint else tint.copy(alpha = 0.28f),
                topLeft = Offset(i * slot + (slot - bw) / 2f, size.height - h),
                size = Size(bw, h)
            )
        }
    }
}

/** Seconds elapsed since [sinceMs], for the sweep age and the on-air timer. */
fun since(sinceMs: Long): String {
    if (sinceMs <= 0L) return "—"
    val s = ((System.currentTimeMillis() - sinceMs) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        else -> "${s / 86400}d"
    }
}

/** Recomposes [every] so ages never go stale on a screen someone is staring at. */
@Composable
fun Ticker(every: Long = 1000L): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(every) { while (true) { delay(every); now = System.currentTimeMillis() } }
    return now
}

/**
 * The radio, in words. `Off` and `Scanning` are not the same state to a field crew - one is a
 * radio that is not listening - so they do not share a colour and neither is decorative.
 */
fun radioWord(state: RadioState): String = when (state) {
    RadioState.Off -> "Radio off"
    RadioState.Scanning -> "Sweeping"
    RadioState.Live -> "Mesh live"
    RadioState.RadioMissing -> "No radio"
    RadioState.PermissionNeeded -> "Allow radio"
}

fun radioTone(state: RadioState): ChipTone = when (state) {
    RadioState.Live -> ChipTone.Live
    RadioState.Scanning -> ChipTone.On
    RadioState.Off -> ChipTone.Warn
    else -> ChipTone.Warn
}

/**
 * A ruled language selector for the panes that have one language to name and no room for the grid.
 * The list opens in its own popup: it used to push the panel's content down as it grew, which on
 * the bench meant the first thing a selector did was hide the thread you were watching it choose.
 */
@Composable
fun LangDropdown(
    value: Lang,
    options: List<Lang>,
    onPick: (Lang) -> Unit,
    modifier: Modifier = Modifier,
    enabledFor: (Lang) -> Boolean = { true },
    noteFor: (Lang) -> String? = { null },
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = VaniTarget)
                .background(if (open) VaniColors.PanelLit else VaniColors.Panel, RoundedCornerShape(VaniRadius))
                .border(1.dp, if (open) VaniColors.Ink else VaniColors.Rule, RoundedCornerShape(VaniRadius))
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value.native, style = VaniType.titleMedium, color = VaniColors.Ink)
            Spacer(Modifier.width(9.dp))
            Text(
                value.label, style = VaniType.labelSmall, color = VaniColors.InkDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (open) "close" else "choose another language",
                tint = VaniColors.InkDim, modifier = Modifier.size(22.dp)
            )
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 268.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                options.forEach { lg ->
                    val usable = enabledFor(lg)
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
                            .background(
                                if (lg == value) VaniColors.PanelLit else Color.Transparent,
                                RoundedCornerShape(VaniRadius)
                            )
                            .clickable(enabled = usable, role = Role.Button) { onPick(lg); open = false }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                lg.native, style = VaniType.titleMedium,
                                color = when {
                                    lg == value -> VaniColors.Ink
                                    usable -> VaniColors.Ink
                                    else -> VaniColors.InkFaint
                                }
                            )
                            noteFor(lg)?.let {
                                Text(it, style = VaniType.labelSmall, color = VaniColors.InkDim,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        if (lg == value) Icon(Icons.Outlined.Check, null, tint = VaniColors.Ink,
                            modifier = Modifier.size(16.dp))
                        else Text(lg.label, style = VaniType.labelSmall, color = VaniColors.InkFaint, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The glyphs the design draws inline; outlined so their weight matches the hairlines. */
object VaniIcons {
    val Bluetooth = Icons.Outlined.Bluetooth
    val Mic = Icons.Outlined.Mic
    val Speak = Icons.Outlined.VolumeUp
    val Replay = Icons.Outlined.PlayArrow
    val Check = Icons.Outlined.Check
    val Info = Icons.Outlined.Info
    /** The frame's own glyph: bytes that left on the air. */
    val Air = Icons.Outlined.Send
}

/** A row of the floating nav. Kept here so Talk, Mesh, Setup and Demo share one geometry. */
@Composable
fun NavCell(
    label: String,
    icon: ImageVector,
    current: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.widthIn(min = 72.dp)
            .background(
                if (current) VaniColors.InkGhost else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, tint = if (current) VaniColors.Ink else VaniColors.InkDim,
                modifier = Modifier.size(18.dp))
            Text(
                label.uppercase(), style = VaniLabel.tab,
                color = if (current) VaniColors.Ink else VaniColors.InkDim,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

/** Modifier helper kept local so panes do not each invent their own gutter. */
fun Modifier.screenGutter() = this.padding(horizontal = 20.dp)

/**
 * The scroll body's bottom clearance: the nav floats above the 16dp it owns plus the system gesture
 * bar, so content has to clear all three. Measured on device at 104dp the last caption of the Mesh
 * pane sat under the pill, which is why this is generous rather than tight.
 */
val NavClearance = 128.dp
