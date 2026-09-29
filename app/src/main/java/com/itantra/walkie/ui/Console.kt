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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.unit.times
import com.itantra.walkie.Lang
import com.itantra.walkie.Msg
import com.itantra.walkie.Pane
import com.itantra.walkie.WalkieViewModel
import com.itantra.walkie.net.Address
import com.itantra.walkie.net.Proximity
import kotlin.math.roundToInt
import com.itantra.walkie.net.RadioState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * The console: the design's three surfaces plus the bench the operator asked for.
 *
 * `vani-mesh.html`, `vani-talk.html` and `vani-setup.html` are ported screen for screen; the fourth
 * cell is the single-handset loopback bench, which the export does not know about but the product
 * brief does - the user asked for it by name on 2026-09-25 because that is how the chain gets
 * tested with one handset in hand. It is a real pane over the real engines, not dummy content.
 *
 * The navigation floats over the content rather than pushing it, exactly as the export draws it,
 * and every pane leaves [NavClearance] at the bottom so nothing important hides under it.
 */
@Composable
fun Console(
    vm: WalkieViewModel,
    onEnableRadio: () -> Unit,
    onGrantRadio: () -> Unit,
    requestMic: () -> Boolean,
) {
    val dest = vm.ui.pane
    Box(
        Modifier.fillMaxSize().background(VaniColors.Ground).imePadding()
    ) {
        when (dest) {
            Pane.Mesh -> MeshPane(vm, onEnableRadio, onGrantRadio)
            Pane.Talk -> TalkPane(vm, requestMic)
            Pane.Setup -> SetupPanel(vm, firstRun = false, onDone = { })
            Pane.Demo -> DemoPanel(vm, requestMic)
        }
        VaniNavBar(dest, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { vm.selectPane(it) }
    }
}

/** `.app-top` - the screen's name, what it is for, and the one control that is not the main action. */
@Composable
internal fun AppTop(
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = { VaniWordmark() },
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = VaniType.headlineMedium, color = VaniColors.Ink)
            Text(sub, style = VaniType.titleSmall, color = VaniColors.InkDim)
        }
        trailing()
    }
}

// ------------------------------------------------------------------ mesh

@Composable
private fun MeshPane(
    vm: WalkieViewModel,
    onEnableRadio: () -> Unit,
    onGrantRadio: () -> Unit,
) {
    val mesh = vm.mesh
    val now = Ticker()
    val reached = mesh.nodesReached
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppTop(
            title = "Mesh",
            sub = "People within Bluetooth range",
            trailing = {
                Chip(
                    text = radioWord(mesh.radio),
                    icon = VaniIcons.Bluetooth,
                    tone = radioTone(mesh.radio),
                )
            }
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .screenGutter().padding(bottom = NavClearance)
        ) {
            when (mesh.radio) {
                // Android 12+ will not let this app enable the radio, so the screen says who can.
                RadioState.Off, RadioState.PermissionNeeded -> RadioOffPane(
                    permission = mesh.radio == RadioState.PermissionNeeded,
                    missing = false,
                    onEnableRadio = onEnableRadio,
                    onGrantRadio = onGrantRadio,
                )
                RadioState.RadioMissing -> RadioOffPane(permission = false, missing = true, {}, {})
                else -> {
                    if (mesh.peers.isEmpty()) {
                        if (mesh.lastSweepMs == 0L) ScanLine("Scanning for VANI devices…")
                        else Notice(
                            "No phone in range. Advertising and listening over Bluetooth - " +
                                "VANI on another handset shows up here by itself, there is nothing to pair."
                        )
                    }
                    PeerSection(vm, reached, now)
                    // The delivery caveat is not stated here any more, on purpose: each bubble
                    // already carries its own truth - "on air · N in range", or "not sent - no
                    // verified link" - so a permanent box above the list would only be the same
                    // sentence shouted at everyone whether or not it applies to their message.
                    Spacer(Modifier.height(16.dp))
                }
            }
            YouCard(vm)
        }
    }
}

@Composable
private fun RadioOffPane(
    permission: Boolean,
    missing: Boolean,
    onEnableRadio: () -> Unit,
    onGrantRadio: () -> Unit,
) {
    Notice(
        if (missing) "This phone has no Bluetooth radio, so nothing can leave it. Text still works " +
            "on the bench, and every language still translates on this handset."
        else if (permission) "VANI needs your permission to use Bluetooth. One tap, then this phone " +
            "joins the mesh."
        else "VANI cannot turn Bluetooth on by itself - Android 12+ requires you to allow it. " +
            "One tap, then the phone joins the mesh."
    )
    if (!missing) {
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            label = if (permission) "Allow Bluetooth" else "Turn on Bluetooth",
            icon = VaniIcons.Bluetooth,
            onClick = if (permission) onGrantRadio else onEnableRadio,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Radio use is continuous while VANI is open; it is the cost of working with no " +
                "network. Close the app to stop it.",
            style = VaniType.labelSmall, color = VaniColors.InkFaint
        )
    }
}

@Composable
private fun PeerSection(vm: WalkieViewModel, reached: Int, now: Long) {
    val peers = vm.mesh.peers
    // The scan line and the "nothing in range" notice are both full-width blocks; 4dp under them
    // reads as one card touching the next.
    Spacer(Modifier.height(16.dp))
    // While the first sweep is still out there the scan line above already says so; a second
    // "listening…" under it is the same sentence twice.
    if (peers.isNotEmpty() || vm.mesh.lastSweepMs != 0L) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(
                "$reached ${if (reached == 1) "phone" else "phones"} in range · " +
                    if (vm.mesh.lastSweepMs == 0L) "nothing heard yet"
                    else "swept ${since(vm.mesh.lastSweepMs)} ago",
                style = VaniType.labelMedium, color = VaniColors.InkDim, modifier = Modifier.weight(1f)
            )
        }
    }
    // The export offers a Rescan pill, but this radio sweeps continuously, so a button that
    // restarted it would either do nothing or drop live links. The age above is the honest
    // version of the same information.
    BroadcastRow(vm, lit = vm.ui.active == Address.ALL_ID)
    Spacer(Modifier.height(8.dp))
    peers.forEachIndexed { i, p ->
        val named = p.name.isNotBlank()
        val voice = vm.hasVoice(p.lang)
        // Ground distance between two GPS fixes. This is the one separation the console is
        // allowed to print as a number: it comes from two positions, not from signal strength,
        // and it only appears when both handsets actually have a fix.
        val gap = listOfNotNull(vm.fixes.fix, p.pos).takeIf { it.size == 2 }?.let {
            val d = it[0].distanceM(it[1])
            if (d < 950) "${d.roundToInt()} m from you" else "${"%.1f".format(d / 1000)} km from you"
        }
        // One smoothed level drives the bar, the steps and the word; the arrow is the only thing
        // that needs the whole window, because a direction is a slope and a slope needs samples.
        val samples = vm.mesh.proximitySamples(p.id)
        val level = Proximity.smoothed(samples) ?: p.rssi
        PeerRow(
            initials = initials(if (named) p.name else p.id),
            name = if (named) p.name else p.id,
            meta = (if (named) "${p.lang.native} · ${p.lang.label}" else "found over Bluetooth") +
                " — " + (if (named) "last heard ${since(p.lastHeardMs)} ago" else "not yet introduced") +
                (gap?.let { " · $it" } ?: "") +
                if (voice) "" else " · typed only",
            rssi = level,
            trend = Proximity.arrow(Proximity.trend(samples)),
            selected = vm.ui.active == p.id,
            modifier = Modifier.padding(top = if (i == 0) 8.dp else 0.dp),
            onClick = { vm.selectChannel(p.id); vm.selectPane(Pane.Talk) },
        )
    }
    Spacer(Modifier.height(16.dp))
}

/** The mesh's own channel: one transmission, every phone translating where it lands. */
@Composable
private fun BroadcastRow(vm: WalkieViewModel, lit: Boolean) {
    val shape = RoundedCornerShape(VaniRadiusBubble)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
            .background(if (lit) VaniColors.PanelLit else VaniColors.Panel, shape)
            .border(1.dp, if (lit) VaniColors.Ink else VaniColors.Rule, shape)
            .clickable(role = Role.Button) { vm.selectChannel(Address.ALL_ID); vm.selectPane(Pane.Talk) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(40.dp).background(VaniColors.PanelRaised, CircleShape)
                .border(1.dp, VaniColors.Rule, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Public, null, tint = VaniColors.InkDim, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("All · broadcast", style = VaniType.titleLarge, color = VaniColors.Ink)
            Text(
                "every phone in range · each translates on receipt",
                style = VaniType.labelMedium, color = VaniColors.InkDim
            )
        }
        Text(
            "${vm.threadFor(Address.ALL_ID).size} line${if (vm.threadFor(Address.ALL_ID).size == 1) "" else "s"}",
            style = VaniType.labelMedium, color = VaniColors.InkFaint
        )
    }
}

@Composable
private fun YouCard(vm: WalkieViewModel) {
    Group("You") {
        VaniCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(initials(vm.ui.name.ifBlank { "VANI" }))
                Column(Modifier.weight(1f)) {
                    Text(
                        vm.ui.name.ifBlank { "This phone" },
                        style = VaniType.titleLarge, color = VaniColors.Ink, maxLines = 1
                    )
                    Text(
                        "${vm.ui.src.native} · ${vm.ui.src.label}",
                        style = VaniType.labelMedium, color = VaniColors.InkDim
                    )
                }
                GhostButton("Edit", onClick = { vm.selectPane(Pane.Setup) }, minHeight = 36.dp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Others see this name in their scan. Set it once in Setup.",
            style = VaniType.labelSmall, color = VaniColors.InkFaint
        )
        Spacer(Modifier.height(6.dp))
        // Position, stated as a fact about the radio rather than a dot on a map nobody can read
        // in the rain: what is going out, how old it is, and how sure the phone is.
        val f = vm.fixes.fix
        val sharing = vm.ui.sharePos
        Text(
            when {
                !sharing -> "Position not shared · turn it on in Setup"
                f == null -> "Sharing position, but no GPS fix yet · " +
                    (vm.fixes.problem.ifBlank { "the receiver is still searching" })
                else -> "Position shared · ±${f.accM} m · fix ${f.ageS()}s old"
            },
            style = VaniLabel.tone,
            color = if (sharing && f == null) VaniColors.Alert else VaniColors.InkDim
        )
    }
}

// ------------------------------------------------------------------ talk

@Composable
private fun TalkPane(vm: WalkieViewModel, requestMic: () -> Boolean) {
    val chan = vm.ui.active
    val peer = vm.peerKnown(chan)
    val broadcast = chan == Address.ALL_ID
    val msgs = vm.threadFor(chan)
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex)
    }
    var draft by rememberSaveable(chan) { mutableStateOf("") }
    val inRange = broadcast || vm.peerById(chan) != null

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PeerHead(vm, peer, broadcast, inRange)
        Box(Modifier.weight(1f)) {
            if (msgs.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Nothing on this channel yet",
                        style = VaniType.titleSmall, color = VaniColors.InkDim
                    )
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
                    modifier = Modifier.fillMaxSize().screenGutter(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // One turn shares an id between the line sent and the line spoken, so the key
                    // has to carry which half it is.
                    items(msgs, key = { "${it.id}-${if (it.outgoing) "out" else "in"}" }) { m ->
                        TurnBubble(m) { vm.replay(m.id) }
                    }
                }
            }
        }
        PttZone(vm, requestMic, draft, { draft = it }, { vm.sendMessage(draft); draft = "" })
        Spacer(Modifier.height(NavClearance))
    }
}

/** `.peer-head` - who this thread is with, what the two phones speak, and where the next send goes. */
@Composable
private fun PeerHead(vm: WalkieViewModel, peer: com.itantra.walkie.net.Peer?, broadcast: Boolean, inRange: Boolean) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (broadcast) {
                Box(
                    Modifier.size(34.dp).background(VaniColors.PanelRaised, CircleShape)
                        .border(1.dp, VaniColors.Rule, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Outlined.Public, null, tint = VaniColors.InkDim, modifier = Modifier.size(16.dp)) }
            } else Avatar(initials(peer?.name?.takeIf { it.isNotBlank() } ?: peer?.id ?: "?"), size = 34.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        broadcast -> "All · broadcast"
                        peer?.name?.isNotBlank() == true -> peer.name
                        else -> peer?.id ?: "No channel chosen"
                    },
                    style = VaniType.titleLarge, color = VaniColors.Ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${vm.ui.src.native} ⇄ ${vm.ui.tgt.native}",
                    style = VaniType.labelMedium, color = VaniColors.InkDim
                )
            }
            Chip(
                text = if (inRange) "In range" else "Out of range",
                tone = if (inRange) ChipTone.On else ChipTone.Warn,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Where the next send goes. The direct pill carries the peer's own name, the way the
            // export labels it, because "Direct" to a crew means the person they are talking to.
            ReplayPill(
                label = peer?.name?.takeIf { it.isNotBlank() }?.substringBefore(" ") ?: "Direct",
                active = !broadcast,
                onClick = { if (broadcast) vm.selectPane(Pane.Mesh) },
            )
            ReplayPill(
                label = "Broadcast", active = broadcast,
                onClick = { vm.selectChannel(Address.ALL_ID) },
            )
        }
        Spacer(Modifier.height(8.dp))
        // What the machine is doing gets its own full-width line: squeezed between two pills it
        // ellipsised "engines ready · 7 voices · mic in 11 langs" down to a fragment, and a status
        // that names half a problem names nothing.
        Text(
            vm.ui.status.ifBlank { "idle" },
            style = VaniType.labelSmall, color = VaniColors.InkFaint,
            textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Rule()
    }
}

/**
 * A turn, drawn the way the design reads one: who and when above the bubble, the words as they were
 * said, the arrow line naming where they went, the result in the reader's language, the tone the
 * caller arrived with, and the delivery claim in words.
 */
@Composable
internal fun TurnBubble(m: Msg, onReplay: () -> Unit) {
    val sent = m.outgoing
    val shape = RoundedCornerShape(
        topStart = VaniRadiusBubble, topEnd = VaniRadiusBubble,
        bottomStart = if (sent) VaniRadiusBubble else 4.dp,
        bottomEnd = if (sent) 4.dp else VaniRadiusBubble,
    )
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (sent) Alignment.End else Alignment.Start
    ) {
        Text(
            "${m.who.ifBlank { if (sent) "You" else "peer" }} · ${TimeFormat.format(Date(m.atMs))}",
            style = VaniLabel.eyebrow, color = VaniColors.InkDim,
            modifier = Modifier.padding(bottom = 4.dp, start = if (sent) 6.dp else 0.dp, end = if (sent) 0.dp else 6.dp)
        )
        Column(Modifier.widthIn(max = 320.dp)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(if (sent) VaniColors.SignalDeep else VaniColors.Panel, shape)
                    .border(1.dp, if (sent) VaniColors.SignalBorder else VaniColors.Rule, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (m.origin.isNotBlank()) {
                    Text(m.origin, style = VaniType.bodySmall, color = VaniColors.InkDim, maxLines = 4,
                        overflow = TextOverflow.Ellipsis)
                    ArrowLine(m.path.ifBlank { "spoken to you" })
                } else if (m.path.isNotBlank()) {
                    ArrowLine(m.path)
                }
                Text(m.text.ifBlank { "…" }, style = VaniType.bodyLarge, color = VaniColors.Ink)
                val cost = wireLine(m)
                if (m.toneLabel.isNotBlank() || cost != null) {
                    Spacer(Modifier.height(8.dp))
                    Rule()
                    Spacer(Modifier.height(8.dp))
                    if (m.toneLabel.isNotBlank()) MetaLine(VaniIcons.Mic, m.toneLabel)
                    if (cost != null) MetaLine(VaniIcons.Air, cost)
                }
            }
            if (m.status.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp)
                ) {
                    Icon(
                        if (m.statusOk) VaniIcons.Check else VaniIcons.Info, null,
                        tint = if (m.statusOk) VaniColors.InkDim else VaniColors.Alert,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        m.status, style = VaniType.labelMedium.copy(letterSpacing = 0.2.sp),
                        color = if (m.statusOk) VaniColors.InkDim else VaniColors.Alert, maxLines = 2
                    )
                }
            }
            if (m.hasAudio) {
                ReplayPill(
                    label = if (m.speaking) "Playing…" else "Replay voice",
                    active = m.speaking,
                    icon = Icons.Outlined.PlayArrow,
                    onClick = onReplay,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** One measured fact about a turn, in the machine's own voice. */
@Composable
private fun MetaLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        Icon(icon, null, tint = VaniColors.InkDim, modifier = Modifier.size(12.dp))
        Text(text, style = VaniLabel.tone, color = VaniColors.InkDim, maxLines = 1)
    }
}

/**
 * Bytes on the wire against bytes as plain text, in the machine's own voice. A frame that came out
 * no smaller says so too: the codec picks the literal encoding whenever its model would lose, and
 * hiding that would put the one dishonest number on the screen.
 */
private fun wireWords(on: Int, plain: Int, verb: String): String = when {
    plain <= 0 -> "$on B $verb"
    on >= plain -> "$on B $verb · plain text, nothing saved"
    else -> "$on B $verb · $plain B as text · −${100 - on * 100 / plain}%"
}

/**
 * What this turn cost on the wire. Null for a line that never went on air: a bench bubble says
 * nothing rather than quoting a saving no radio delivered.
 */
private fun wireLine(m: Msg): String? =
    if (m.wireBytes <= 0) null else wireWords(m.wireBytes, m.plainBytes, "on air")

@Composable
private fun ArrowLine(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp).fillMaxWidth()
    ) {
        Text(text, style = VaniLabel.arrow, color = VaniColors.InkDim, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(VaniColors.Rule))
    }
}

// ------------------------------------------------------------------ push to talk

/**
 * `.ptt-zone`: the pipeline naming which leg is running, the hold bar itself, and the typed route
 * around it. Holding rather than tapping is the walkie convention the audience already knows, and
 * the bar's own lettering says what it is doing at every moment, so the state never rides on colour.
 */
@Composable
private fun PttZone(
    vm: WalkieViewModel,
    requestMic: () -> Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    var onAir by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    val blocked = when {
        !vm.ready() -> "Models are still loading"
        !vm.micUsable() -> "No microphone for ${vm.ui.src.native} yet — type it, or change it in Setup"
        else -> null
    }
    val live = onAir && blocked == null
    val now = if (live) Ticker(250L) else System.currentTimeMillis()
    val held = ((now - startedAt) / 1000L).toInt()
    val busy = vm.ui.status.startsWith("decoding") || vm.ui.status.startsWith("incoming") ||
        vm.ui.status.startsWith("speaking")
    val shape = RoundedCornerShape(VaniRadiusBubble)

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        // The rescue strip sits above the transmitter on purpose: a person who can hold a phone
        // can also tap one of these, and neither the keyboard nor the recogniser is trusted to
        // work in water, in noise, or with a shaking hand. The words travel as any sentence does,
        // so the far phone answers them in its own language.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SosButton(vm)
            com.itantra.walkie.ml.Preset.values().forEach { p ->
                if (p == com.itantra.walkie.ml.Preset.HELP) return@forEach
                GhostButton(p.label, minHeight = 34.dp, onClick = { vm.sendPreset(p) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Pipeline(
            visible = live || busy,
            stages = when {
                live -> Triple(StageState.Active, StageState.Waiting, StageState.Waiting)
                vm.ui.status.startsWith("decoding") -> Triple(StageState.Active, StageState.Waiting, StageState.Waiting)
                vm.ui.status.startsWith("incoming") || vm.ui.status.contains("translat") ->
                    Triple(StageState.Done, StageState.Active, StageState.Waiting)
                else -> Triple(StageState.Done, StageState.Done, StageState.Active)
            }
        )
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 76.dp)
                .background(VaniColors.Panel, shape)
                .border(1.dp, if (live) VaniColors.Signal else VaniColors.Rule, shape)
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (live) WaveBars()
            Box(
                Modifier.size(44.dp)
                    .background(if (live) VaniColors.Signal else VaniColors.PanelRaised, CircleShape)
                    .border(1.dp, if (live) VaniColors.Signal else VaniColors.Rule, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Mic, null,
                    tint = when {
                        live -> VaniColors.OnSignal
                        blocked == null -> VaniColors.Ink
                        else -> VaniColors.InkFaint
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    blocked != null -> "Type to send"
                    live -> "Release to send"
                    else -> "Hold to talk"
                },
                style = VaniLabel.stage.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp),
                color = VaniColors.Ink,
            )
            Text(
                when {
                    blocked != null -> blocked
                    live -> "%d:%02d".format(held / 60, held % 60)
                    else -> "${vm.ui.src.native} · ${vm.channelName()}"
                },
                style = VaniType.labelSmall,
                color = if (blocked == null) VaniColors.InkDim else VaniColors.Alert,
                textAlign = TextAlign.Center, maxLines = 2,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VaniField(
                value = draft,
                onValueChange = { if (it.length <= 400) onDraft(it) },
                placeholder = "Or type — then send",
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier.size(48.dp).background(VaniColors.Ground, CircleShape)
                    .border(1.dp, if (draft.isBlank()) VaniColors.Rule else VaniColors.Signal, CircleShape)
                    .clickable(enabled = draft.isNotBlank(), role = Role.Button, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Send, "send",
                    tint = if (draft.isBlank()) VaniColors.InkFaint else VaniColors.Ink,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        // The cost of the draft, coded on this phone before it ever leaves it: the same frame the
        // radio would send, measured as it is typed. Nothing appears when there is no model to
        // measure with, because a preview the device cannot compute is a number, not a promise.
        val cost = vm.wireCost(draft.trim())
        if (cost != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                wireWords(cost.first, cost.second, "to send"),
                style = VaniLabel.tone, color = VaniColors.InkFaint,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/**
 * The distress beacon, in the design's warn tone rather than the signal green: it is the one
 * control on this screen that is allowed to look urgent, and it always carries its own word, so
 * the state never rides on colour alone. Live, it counts down the window it will stop by itself
 * at - a beacon nobody can turn off keeps boats away from the roof that actually needs them.
 */
@Composable
private fun SosButton(vm: WalkieViewModel) {
    val live = vm.ui.sosLive
    val now = if (live) Ticker(5000L) else 0L
    val mins = if (live) {
        ((vm.ui.sosUntilMs - now) / 60_000L).coerceAtLeast(0L) + 1L
    } else 0L
    val shape = RoundedCornerShape(VaniRadiusPill)
    Box(
        Modifier.background(if (live) VaniColors.Alert else Color.Transparent, shape)
            .border(1.dp, VaniColors.Alert.copy(alpha = if (live) 1f else 0.65f), shape)
            .clickable(role = Role.Button) { if (live) vm.cancelSos() else vm.startSos() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            if (live) "SOS LIVE · ${mins}m LEFT · STOP" else "SOS",
            style = VaniLabel.stage.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp),
            color = if (live) VaniColors.Ground else VaniColors.Alert, maxLines = 1
        )
    }
}

/** `.wave` - the seven bars that move while the microphone is open, and only then. */
@Composable
private fun WaveBars() {
    val reduce = reduceMotion()
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "wave")
    val heights = listOf(8, 16, 26, 18, 30, 14, 22)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        heights.forEachIndexed { i, h ->
            val s by transition.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    tween(550, delayMillis = i * 70, easing = FastOutSlowInEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ), label = "b$i"
            )
            Box(
                Modifier.width(3.dp).height((if (reduce) 1f else s) * h.dp)
                    .background(VaniColors.Signal, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ------------------------------------------------------------------ navigation

/**
 * `.tabbar` - the floating pill. The active cell is `--fg` on a 10% wash, never the accent: the
 * design allows the mint twice per screen and spends it on the transmitter and the primary action.
 */
@Composable
private fun VaniNavBar(dest: Pane, modifier: Modifier = Modifier, onSelect: (Pane) -> Unit) {
    Row(
        modifier.padding(bottom = 16.dp)
            .background(VaniColors.PanelRaised, RoundedCornerShape(18.dp))
            .border(1.dp, VaniColors.Rule, RoundedCornerShape(18.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Pane.values().forEach { d ->
            NavCell(
                label = when (d) {
                    Pane.Mesh -> "Mesh"; Pane.Talk -> "Talk"; Pane.Setup -> "Setup"; Pane.Demo -> "Demo"
                },
                icon = when (d) {
                    Pane.Mesh -> Icons.Outlined.Groups
                    Pane.Talk -> Icons.Outlined.Mic
                    Pane.Setup -> Icons.Outlined.Tune
                    Pane.Demo -> VaniIcons.Replay
                },
                current = d == dest,
                onClick = { onSelect(d) },
            )
        }
    }
}

// ------------------------------------------------------------------ shared bits

/** Two initials from a name, the way the design labels a peer: "Meena K." to "MK". */
private fun initials(name: String): String =
    name.trim().split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString("").uppercase()
        .ifBlank { "V" }

/** The OS "remove animations" setting, which a native app obeys rather than reinterprets. */
@Composable
internal fun reduceMotion(): Boolean {
    val cr = LocalView.current.context.contentResolver
    return remember(cr) {
        runCatching {
            android.provider.Settings.Global.getFloat(cr, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        }.getOrDefault(1f) == 0f
    }
}

/** The brand mark, kept for the panes that show it. It is lettering, not a logo image. */
@Composable
internal fun VaniWordmark(modifier: Modifier = Modifier) {
    Text(
        "VANI", style = VaniLabel.tab.copy(fontSize = 12.sp, letterSpacing = 2.sp),
        fontWeight = FontWeight.Bold, color = VaniColors.Ink, modifier = modifier
    )
}
