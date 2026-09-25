package com.itantra.walkie.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.walkie.Lang
import com.itantra.walkie.Msg
import com.itantra.walkie.Pane
import com.itantra.walkie.WalkieViewModel
import com.itantra.walkie.net.Address
import com.itantra.walkie.net.MeshTransport
import com.itantra.walkie.net.RadioState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * The console: three working panes and one bench over one radio. Channels is the status board of
 * who is reachable, On air is the lit channel's traffic, Identity is who this phone is to the rest
 * of the mesh, and Demo runs both handsets on this one phone so the chain can be tested alone.
 * The talk bar sits above the navigation on the working panes, because changing screen does not
 * put the microphone down.
 */
@Composable
fun Console(vm: WalkieViewModel, onEnableRadio: () -> Unit, onGrantRadio: () -> Unit,
            requestMic: () -> Boolean) {
    val dest = vm.ui.pane
    Column(
        Modifier
            .fillMaxSize()
            .background(VaniColors.Ground)
            .imePadding()
    ) {
        MeshStrip(vm, onEnableRadio, onGrantRadio, modifier = Modifier.statusBarsPadding())
        Box(Modifier.weight(1f)) {
            when (dest) {
                Pane.Channels -> Roster(vm, vm.mesh)
                Pane.OnAir -> ChannelThread(vm)
                Pane.Identity -> SetupPanel(vm, firstRun = false, onDone = { })
                Pane.Demo -> DemoPanel(vm, requestMic)
            }
        }
        StatusReadout(vm)
        // The bench owns its own microphone button: two live capture bars on one screen would
        // both be holding the same recorder, and the one behind could not say whose words it got.
        if (dest != Pane.Demo) TalkBar(vm, requestMic)
        VaniNavBar(dest) { vm.selectPane(it) }
    }
}

/**
 * The console's cursor line. What the engines are doing and what a turn cost is real output of
 * this app - the numbers are how a field team tells a working radio from a decorative one - so it
 * stays on screen in the machine face rather than being dressed up.
 */
@Composable
private fun StatusReadout(vm: WalkieViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            vm.ui.status.ifBlank { "idle" },
            style = VaniType.labelSmall, color = VaniColors.InkFaint,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (vm.ui.turbo) "turbo solve" else "full solve",
            style = VaniType.labelSmall, color = VaniColors.InkFaint
        )
    }
}

// ------------------------------------------------------------------ mesh status

@Composable
private fun MeshStrip(vm: WalkieViewModel, onEnableRadio: () -> Unit, onGrantRadio: () -> Unit,
                      modifier: Modifier = Modifier) {
    val mesh = vm.mesh
    val now = Ticker()
    val reached = mesh.nodesReached
    Column(modifier.fillMaxWidth().background(VaniColors.Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VaniWordmark(health = reached / 6f, modifier = Modifier.width(104.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caps(radioWord(mesh.radio), VaniType.labelMedium, color = radioColor(mesh.radio))
                    Spacer(Modifier.width(7.dp))
                    // Pulse rate is the mesh's own rhythm: a settling sweep breathes faster than a
                    // full roster, so the lamp tells you how the radio is doing, not just that it is on.
                    val period = 1600L - (reached.coerceAtMost(6) * 180L)
                    val on = now / period % 2L == 0L
                    Lamp(if (on) radioColor(mesh.radio) else radioColor(mesh.radio).copy(alpha = 0.25f), ring = true)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "$reached node${if (reached == 1) "" else "s"} · " +
                        if (mesh.lastSweepMs == 0L) "nothing heard yet"
                        else "swept ${since(mesh.lastSweepMs)} ago",
                    style = VaniType.labelSmall, color = VaniColors.InkFaint
                )
            }
        }
        // Each radio condition a person can fix says what is wrong and offers the fix.
        val blocked: Triple<String, String, (() -> Unit)?>? = when (mesh.radio) {
            RadioState.Off ->
                Triple("Bluetooth is off — no other phone can be reached", "Turn on", onEnableRadio)
            RadioState.PermissionNeeded ->
                Triple("VANI needs permission to use Bluetooth", "Allow", onGrantRadio)
            RadioState.RadioMissing ->
                Triple("This phone has no Bluetooth radio — nothing can leave it", "", null)
            else -> null
        }
        if (blocked != null) {
            Rule(color = VaniColors.PanelLit)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(VaniColors.AlertDeep)
                    .clickable(enabled = blocked.third != null, role = Role.Button) { blocked.third?.invoke() }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    blocked.first,
                    style = VaniType.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
                    color = VaniColors.Alert,
                    modifier = Modifier.weight(1f)
                )
                if (blocked.second.isNotEmpty()) {
                    Caps(blocked.second, VaniType.labelMedium, color = VaniColors.Ink)
                }
            }
        }
    }
    Rule()
}

// ------------------------------------------------------------------ the status board

@Composable
private fun Roster(vm: WalkieViewModel, mesh: MeshTransport) {
    val peers = mesh.peers
    val lit = vm.ui.active
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ChannelRow(
                title = "ALL · broadcast",
                sub = "every phone in range · each translates on receipt",
                tag = "${mesh.nodesReached} in range",
                preview = vm.threadFor(Address.ALL_ID).lastOrNull(),
                lines = vm.threadFor(Address.ALL_ID).size,
                lit = lit == Address.ALL_ID,
                index = 0,
                leading = { Icon(Icons.Filled.Language, null, tint = VaniColors.Signal, modifier = Modifier.size(21.dp)) },
                onClick = { vm.selectChannel(Address.ALL_ID) }
            )
            Rule(color = VaniColors.PanelLit)
        }
        if (peers.isEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Lamp(VaniColors.Alert, ring = true)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("No phone in range", style = VaniType.titleMedium, color = VaniColors.Ink)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Advertising and listening over Bluetooth. VANI on another handset " +
                                "shows up here by itself — there is nothing to pair.",
                            style = VaniType.labelSmall, color = VaniColors.InkFaint, maxLines = 3
                        )
                    }
                }
                Rule(color = VaniColors.PanelLit)
            }
            return@LazyColumn
        }
        items(peers, key = { it.id }) { p ->
            // A node seen but not yet introduced is listed by address. The name arrives in the
            // handshake, and inventing one earlier would be the same lie as a fake node.
            val named = p.name.isNotBlank()
            ChannelRow(
                title = if (named) p.name else p.id,
                sub = if (named) "last heard ${since(p.lastHeardMs)} ago · ${p.rssi} dBm"
                else "found over Bluetooth · not yet introduced",
                tag = if (named) p.lang.native else "—",
                bars = p.bars,
                age = since(p.lastHeardMs),
                preview = vm.threadFor(p.id).lastOrNull(),
                lines = vm.threadFor(p.id).size,
                lit = lit == p.id,
                index = peers.indexOf(p) + 1,
                onClick = { vm.selectChannel(p.id) }
            )
            Rule(color = VaniColors.PanelLit)
        }
    }
}

/**
 * One ruled channel: name, what it really is, signal and age — and, once traffic exists, the
 * last line on it. The board doubles as the traffic readout so the roster is never just a picker.
 */
@Composable
private fun ChannelRow(
    title: String,
    sub: String,
    tag: String,
    preview: Msg?,
    lines: Int,
    lit: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    bars: Int = -1,
    age: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val flap = joinFlap(index)
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 68.dp)
            .background(if (lit) VaniColors.PanelLit else Color.Transparent)
            .graphicsLayer {
                rotationX = -58f * (1f - flap)
                cameraDistance = 14f * density
                alpha = 0.2f + 0.8f * flap
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { leading?.invoke() }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = VaniType.titleLarge.copy(fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium),
                    color = if (lit) VaniColors.Ink else VaniColors.InkDim,
                    maxLines = 1
                )
                if (lit) {
                    Spacer(Modifier.width(8.dp))
                    Lamp(VaniColors.Alert)
                    Spacer(Modifier.width(5.dp))
                    Caps("LIT", VaniType.labelSmall.copy(fontWeight = FontWeight.Bold), color = VaniColors.Alert)
                }
            }
            Spacer(Modifier.height(3.dp))
            if (preview != null) {
                Text(
                    preview.text,
                    style = VaniType.bodySmall.copy(fontWeight = if (preview.outgoing) FontWeight.Normal else FontWeight.Medium),
                    color = VaniColors.Ink,
                    maxLines = 1
                )
            } else {
                Text(sub, style = VaniType.labelSmall, color = VaniColors.InkFaint, maxLines = 2)
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangTag(if (lines > 0) "$tag · $lines line${if (lines == 1) "" else "s"}" else tag)
                if (bars >= 0) {
                    Spacer(Modifier.width(10.dp))
                    SignalBars(bars = bars, tint = if (bars <= 1) VaniColors.Alert else VaniColors.InkDim)
                    Spacer(Modifier.width(6.dp))
                    Text("${bars}/4", style = VaniType.labelSmall, color = VaniColors.InkFaint)
                }
            }
        }
        if (age != null) {
            Column(Modifier.width(52.dp), horizontalAlignment = Alignment.End) {
                Text(age, style = VaniType.labelSmall, color = VaniColors.InkFaint)
                Text("ago", style = VaniType.labelSmall, color = VaniColors.InkFaint.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun LangTag(text: String) {
    Box(
        Modifier
            .background(VaniColors.PanelRaised, RoundedCornerShape(VaniRadiusChip))
            .border(1.dp, VaniColors.Rule, RoundedCornerShape(VaniRadiusChip))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = VaniType.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = VaniColors.InkDim)
    }
}

/**
 * The split-flap train: a node turning up in the sweep rotates down into place, staggered by its
 * position, so the mesh arriving is something watched rather than read.
 */
@Composable
private fun joinFlap(index: Int): Float {
    val reduce = reduceMotion()
    val a = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) a.animateTo(1f, tween(380, delayMillis = 60 + index * 70, easing = FastOutSlowInEasing))
    }
    return a.value
}

/** The OS "remove animations" setting, which a native app obeys rather than reinterprets. */
@Composable
private fun reduceMotion(): Boolean {
    val cr = LocalView.current.context.contentResolver
    return remember(cr) {
        runCatching {
            android.provider.Settings.Global.getFloat(cr, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        }.getOrDefault(1f) == 0f
    }
}

// ------------------------------------------------------------------ the lit channel

@Composable
private fun ChannelThread(vm: WalkieViewModel) {
    val chan = vm.ui.active
    val peer = vm.peerKnown(chan)
    val inRange = chan == Address.ALL_ID || vm.peerById(chan) != null
    val msgs = vm.threadFor(chan)
    val state = when {
        !inRange -> "out of range"
        vm.channelDeliverable() -> "can deliver"
        else -> "cannot deliver"
    }
    val stateColor = if (state == "can deliver") VaniColors.Signal else VaniColors.Alert
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex)
    }
    var draft by rememberSaveable(chan) { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Caps(
                    if (chan == Address.ALL_ID) "Broadcast"
                    else peer?.name?.takeIf { it.isNotBlank() } ?: chan,
                    VaniType.titleSmall.copy(fontWeight = FontWeight.Bold), color = VaniColors.InkDim
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        chan == Address.ALL_ID -> "one transmission · every phone translates on receipt"
                        !inRange -> "last heard ${since(peer?.lastHeardMs ?: 0L)} ago · nothing reaches it now"
                        else -> "personal · what arrives is turned into ${vm.ui.tgt.native} here"
                    },
                    style = VaniType.labelSmall, color = VaniColors.InkFaint, maxLines = 2
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state,
                    style = VaniType.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = stateColor
                )
                Spacer(Modifier.width(6.dp))
                Lamp(stateColor)
            }
        }
        Rule()
        Box(Modifier.weight(1f)) {
            if (msgs.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Caps("Nothing on this channel yet", VaniType.titleSmall, color = VaniColors.InkDim)
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "Hold the bar below and speak, or type. Your words go out as you said them; " +
                            "the phone that receives them does the translating.",
                        style = VaniType.bodySmall, color = VaniColors.InkFaint, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    // One turn shares an id between the line sent and the line spoken, so the
                    // bubble key has to carry which half it is.
                    items(msgs, key = { "${it.id}-${if (it.outgoing) "out" else "in"}" }) { m ->
                        Bubble(m) { vm.replay(m.id) }
                    }
                }
            }
        }
        Composer(vm, draft, onDraft = { draft = it }, onSend = { vm.sendMessage(draft); draft = "" })
    }
}

/** Reading order everyone already knows: mine right in signal green, theirs left in panel grey. */
@Composable
internal fun Bubble(m: Msg, onReplay: () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = VaniRadiusBubble, topEnd = VaniRadiusBubble,
        bottomStart = if (m.outgoing) VaniRadiusBubble else VaniRadiusChip,
        bottomEnd = if (m.outgoing) VaniRadiusChip else VaniRadiusBubble
    )
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start
    ) {
        Column(Modifier.widthIn(max = 306.dp)) {
            Row(
                Modifier
                    .background(if (m.outgoing) VaniColors.SignalDeep else VaniColors.PanelRaised, shape)
                    .border(1.dp, if (m.outgoing) VaniColors.SignalEdge else VaniColors.Rule, shape)
                    .padding(start = 13.dp, end = 8.dp, top = 10.dp, bottom = 9.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(m.text.ifBlank { "…" }, style = VaniType.bodyLarge, color = VaniColors.Ink)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        TimeFormat.format(Date(m.atMs)),
                        style = VaniType.labelSmall, color = VaniColors.InkFaint,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
                if (m.hasAudio) {
                    Spacer(Modifier.width(2.dp))
                    Box(
                        Modifier.size(44.dp).clickable(role = Role.Button, onClick = onReplay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (m.speaking) Icons.Filled.VolumeUp else Icons.Outlined.VolumeUp,
                            contentDescription = if (m.speaking) "speaking" else "play this line again",
                            tint = if (m.speaking) VaniColors.Alert else VaniColors.InkDim,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (m.note.isNotBlank()) {
                Text(
                    m.note,
                    style = VaniType.labelSmall,
                    color = if (m.speaking) VaniColors.Alert else VaniColors.InkFaint,
                    textAlign = if (m.outgoing) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun Composer(vm: WalkieViewModel, draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    Rule()
    if (!vm.channelDeliverable()) {
        Text(
            "No live link to this channel. Sending still writes the line here, marked not sent.",
            style = VaniType.labelSmall, color = VaniColors.Alert,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 9.dp)
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConsoleField(
            value = draft,
            onValueChange = { if (it.length <= 400) onDraft(it) },
            placeholder = "Type to send on this channel…",
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(48.dp)
                .background(if (draft.isBlank()) VaniColors.PanelRaised else VaniColors.Signal, CircleShape)
                .border(1.dp, if (draft.isBlank()) VaniColors.Rule else VaniColors.SignalEdge, CircleShape)
                .clickable(enabled = draft.isNotBlank(), role = Role.Button) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Send, "send",
                tint = if (draft.isBlank()) VaniColors.InkFaint else VaniColors.OnSignal,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ push to talk

/**
 * Hold to talk: press opens the microphone and turns the bar the colour of being on air, release
 * closes it and routes what was said to the lit channel. Holding rather than tapping is the
 * walkie convention the audience already knows, and it makes the transmit state unmissable.
 */
@Composable
private fun TalkBar(vm: WalkieViewModel, requestMic: () -> Boolean) {
    var onAir by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    val blocked = when {
        !vm.ready() -> "models are still loading"
        !vm.micUsable() -> "speech recognition exists for ${Lang.HI.native} only — type on the channel instead"
        else -> null
    }
    val live = onAir && blocked == null
    val now = if (live) Ticker(250L) else System.currentTimeMillis()
    val held = if (live) "${(((now - startedAt) / 1000L)).toInt()}s on air" else vm.ui.src.native
    val shape = RoundedCornerShape(VaniRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
            .height(76.dp)
            .background(if (live) VaniColors.Signal else VaniColors.PanelRaised, shape)
            .border(1.dp, if (live) VaniColors.SignalEdge else VaniColors.Rule, shape)
            .pointerInput(blocked == null) {
                awaitEachGesture {
                    if (blocked != null) return@awaitEachGesture
                    awaitFirstDown(requireUnconsumed = false)
                    if (!requestMic()) return@awaitEachGesture
                    startedAt = System.currentTimeMillis()
                    onAir = true
                    vm.startTalk()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    onAir = false
                    vm.finishTalk()
                }
            }
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Mic, null,
            tint = if (live) VaniColors.OnSignal else if (blocked == null) VaniColors.Signal else VaniColors.InkFaint,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    blocked != null -> "Push to talk unavailable"
                    live -> "On air — release to send"
                    else -> "Hold to talk"
                },
                style = VaniType.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (live) VaniColors.OnSignal else VaniColors.Ink
            )
            Spacer(Modifier.height(3.dp))
            Text(
                blocked ?: "${vm.channelName()} · $held",
                style = VaniType.labelSmall,
                color = if (live) VaniColors.OnSignal.copy(alpha = 0.85f) else VaniColors.InkFaint,
                maxLines = 2
            )
        }
        if (live) SignalBars(bars = 4, tint = VaniColors.OnSignal)
        else Lamp(if (blocked == null) VaniColors.Signal else VaniColors.InkFaint, ring = true)
    }
}

// ------------------------------------------------------------------ navigation

/**
 * A floating Material navigation bar: the destinations are what a dispatcher does plus the bench
 * that proves it works, and the bar carries the brand surface rather than a default elevated sheet.
 */
@Composable
private fun VaniNavBar(dest: Pane, onSelect: (Pane) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
            .height(62.dp)
            .background(VaniColors.PanelRaised, RoundedCornerShape(18.dp))
            .border(1.dp, VaniColors.Rule, RoundedCornerShape(18.dp))
    ) {
        Pane.values().forEach { d ->
            val selected = d == dest
            NavCell(
                label = when (d) {
                    Pane.Channels -> "Channels"
                    Pane.OnAir -> "On air"
                    Pane.Identity -> "Identity"
                    Pane.Demo -> "Demo"
                },
                selected = selected,
                modifier = Modifier.weight(1f),
                icon = {
                    val (filled, outline) = when (d) {
                        Pane.Channels -> Icons.Filled.CellTower to Icons.Outlined.CellTower
                        Pane.OnAir -> Icons.Filled.Mic to Icons.Outlined.Mic
                        Pane.Identity -> Icons.Filled.Person to Icons.Outlined.Person
                        Pane.Demo -> Icons.Filled.Sync to Icons.Outlined.Sync
                    }
                    Icon(
                        if (selected) filled else outline, null,
                        tint = if (selected) VaniColors.Signal else VaniColors.InkFaint,
                        modifier = Modifier.size(22.dp)
                    )
                },
                onClick = { onSelect(d) }
            )
        }
    }
}

@Composable
private fun NavCell(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxHeight().clickable(role = Role.Tab, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .width(60.dp)
                .height(26.dp)
                .background(if (selected) VaniColors.PanelLit else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = VaniType.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = if (selected) VaniColors.Signal else VaniColors.InkFaint
        )
    }
}
