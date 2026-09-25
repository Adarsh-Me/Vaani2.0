package com.itantra.walkie.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itantra.walkie.Lang
import com.itantra.walkie.net.RadioState
import kotlinx.coroutines.delay

/**
 * The console's hairline. This is the only separator the design uses: the panel is a ruled
 * grid, not a stack of cards.
 */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = VaniColors.Rule) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * Received-signal strength as four graduated bars, drawn rather than glyphed so the weight
 * matches the rest of the panel's marks. Empty steps stay visible at low alpha: "no bars"
 * must read as a distant node, not as a missing icon.
 */
@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier, tint: Color = VaniColors.InkDim) {
    Canvas(modifier = modifier.size(19.dp, 15.dp)) {
        val slot = size.width / 4f
        val bw = slot * 0.58f
        for (i in 0 until 4) {
            val h = size.height * (0.28f + 0.24f * i)
            drawRect(
                color = if (i < bars) tint else tint.copy(alpha = 0.22f),
                topLeft = Offset(i * slot + (slot - bw) / 2f, size.height - h),
                size = Size(bw, h)
            )
        }
    }
}

/** A pilot lamp. Paired with a word everywhere it appears, so state never rides on colour. */
@Composable
fun Lamp(color: Color, modifier: Modifier = Modifier, ring: Boolean = false) {
    Box(
        modifier = modifier.size(if (ring) 14.dp else 9.dp)
            .then(if (ring) Modifier.border(1.dp, color.copy(alpha = 0.55f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(if (ring) 7.dp else 5.dp).background(color, CircleShape))
    }
}

/** One line of console lettering: caps, tracking, no sentence case in the chrome. */
@Composable
fun Caps(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = style.color,
) = Text(text, modifier = modifier, style = style.copy(color = color))

/**
 * A selectable value in a ruled set. Used for languages, voice and the two-channel choice:
 * the console offers positions on a panel, not floating pills.
 */
@Composable
fun PanelOption(
    label: String,
    sub: String?,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(VaniRadiusChip)
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = VaniTarget)
            .background(
                when {
                    selected -> VaniColors.SignalDeep
                    !enabled -> VaniColors.Panel
                    else -> VaniColors.PanelRaised
                },
                shape
            )
            .border(
                1.dp,
                when {
                    selected -> VaniColors.SignalEdge
                    !enabled -> VaniColors.PanelLit
                    else -> VaniColors.Rule
                },
                shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
            Text(
                label,
                style = VaniType.titleMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                color = if (selected) VaniColors.Ink else if (enabled) VaniColors.InkDim else VaniColors.InkFaint,
            )
            if (selected) {
                Spacer(Modifier.weight(1f))
                Lamp(VaniColors.Signal)
            }
        }
        if (sub != null) {
            Text(sub, style = VaniType.labelSmall, color = VaniColors.InkFaint, maxLines = 2)
        }
    }
}

/** The one text input in the console world: a ruled field, not a Material outlined box. */
@Composable
fun ConsoleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .background(VaniColors.PanelRaised, RoundedCornerShape(VaniRadiusChip))
            .border(1.dp, VaniColors.Rule, RoundedCornerShape(VaniRadiusChip))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        textStyle = VaniType.bodyMedium.copy(color = VaniColors.Ink),
        cursorBrush = SolidColor(VaniColors.Signal),
        singleLine = singleLine,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = VaniType.bodyMedium.copy(color = VaniColors.InkFaint))
                inner()
            }
        }
    )
}

@Composable
fun ToggleRow(
    label: String,
    sub: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = VaniType.titleMedium)
            Text(sub, style = VaniType.labelSmall, color = VaniColors.InkFaint)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = VaniColors.SignalEdge,
                checkedThumbColor = VaniColors.Signal,
                uncheckedTrackColor = VaniColors.PanelLit,
                uncheckedThumbColor = VaniColors.InkFaint,
            )
        )
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

fun radioWord(state: RadioState): String = when (state) {
    RadioState.Off -> "radio off"
    RadioState.Scanning -> "sweeping"
    RadioState.Live -> "mesh live"
    RadioState.RadioMissing -> "no radio"
    RadioState.PermissionNeeded -> "permission"
}

fun radioColor(state: RadioState): Color = when (state) {
    RadioState.Live -> VaniColors.Signal
    RadioState.Scanning -> VaniColors.Alert
    RadioState.Off -> VaniColors.Alert
    else -> VaniColors.Error
}

/** A group label on the panel. It names what follows; it never decorates a heading. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Caps(text, VaniType.labelMedium, modifier = modifier, color = VaniColors.InkFaint)
}

/**
 * The console's committed action: a solid signal bar, wide enough to hit with a thumb at chest
 * height. Disabled it goes hollow rather than greyed-flat, so the panel keeps its structure.
 */
@Composable
fun ConsoleButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(VaniRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(if (enabled) VaniColors.Signal else Color.Transparent, shape)
            .border(1.dp, if (enabled) VaniColors.Signal else VaniColors.Rule, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = VaniType.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) VaniColors.OnSignal else VaniColors.InkFaint,
        )
    }
}

/** A secondary action: same target, same lettering, no claim on the primary colour. */
@Composable
fun GhostButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(VaniRadius)
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(VaniColors.PanelRaised, shape)
            .border(1.dp, VaniColors.Rule, shape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, style = VaniType.labelLarge, color = VaniColors.Ink)
    }
}

/**
 * A ruled language selector: the value on the face, the whole set behind the tap. The console's
 * other choice surface is [LangGrid], which needs the height of six rows; where a panel has one
 * language to name and no room for a grid, this is the same decision in one line.
 *
 * The list opens in its own popup rather than unfolding inside the panel. It used to push the
 * panel's own content down as it grew, which on the bench meant the first thing a language
 * selector did was hide the thread you were watching it choose for.
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
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = VaniTarget)
                .background(
                    if (open) VaniColors.PanelLit else VaniColors.PanelRaised,
                    RoundedCornerShape(VaniRadiusChip)
                )
                .border(
                    1.dp,
                    if (open) VaniColors.SignalEdge else VaniColors.Rule,
                    RoundedCornerShape(VaniRadiusChip)
                )
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                value.native,
                style = VaniType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = VaniColors.Ink
            )
            Spacer(Modifier.width(9.dp))
            Text(
                value.label,
                style = VaniType.labelSmall, color = VaniColors.InkFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "close" else "choose another language",
                tint = if (open) VaniColors.Signal else VaniColors.InkDim,
                modifier = Modifier.size(22.dp)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 268.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                options.forEach { lg ->
                    val usable = enabledFor(lg)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                            .background(
                                if (lg == value) VaniColors.SignalDeep else Color.Transparent,
                                RoundedCornerShape(VaniRadiusChip)
                            )
                            .clickable(enabled = usable, role = androidx.compose.ui.semantics.Role.Button) {
                                onPick(lg); open = false
                            }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                lg.native,
                                style = VaniType.titleMedium.copy(
                                    fontWeight = if (lg == value) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = when {
                                    lg == value -> VaniColors.Ink
                                    usable -> VaniColors.InkDim
                                    else -> VaniColors.InkFaint
                                }
                            )
                            noteFor(lg)?.let {
                                Text(
                                    it, style = VaniType.labelSmall, color = VaniColors.InkFaint,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        if (lg == value) Lamp(VaniColors.Signal)
                        else Text(
                            lg.label, style = VaniType.labelSmall,
                            color = if (usable) VaniColors.InkFaint else VaniColors.InkFaint.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Two columns of ruled options, for the 11-language set on a phone-width panel. */
@Composable
fun LangGrid(
    langs: List<Lang>,
    selected: List<Lang>,
    enabled: (Lang) -> Boolean = { true },
    subFor: (Lang) -> String? = { null },
    onPick: (Lang) -> Unit,
) {
    val rows = langs.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { lg ->
                    PanelOption(
                        label = lg.native,
                        sub = subFor(lg) ?: lg.label,
                        selected = selected.any { it == lg },
                        enabled = enabled(lg),
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(lg) }
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
