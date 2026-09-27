package com.itantra.walkie.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.walkie.Lang
import com.itantra.walkie.Msg
import com.itantra.walkie.Voice
import com.itantra.walkie.WalkieViewModel

/**
 * The loopback bench: two handsets on this one phone.
 *
 * It exists because the mesh cannot be tested alone - a transmission needs a receiver - and
 * because a demo should never depend on a second handset being charged, unlocked and in range.
 * Phone 1 speaks or types; its words run the identical receive path an inbound frame runs
 * (translate, solve, speak, cache) and land on phone 2's thread. Only the radio is skipped, and
 * the wire row says so rather than dressing a local call up as an over-the-air one.
 *
 * Every number on the page is measured by the same engines the air path uses, which is the point:
 * this is where a language pair gets proven before it is claimed for two phones.
 */
@Composable
fun DemoPanel(vm: WalkieViewModel, requestMic: () -> Boolean) {
    val all = Lang.values().toList()
    val d = vm.ui.demo
    Column(
        Modifier.fillMaxSize().background(VaniColors.Ground).statusBarsPadding()
    ) {
        BenchHead(vm)
        Rule(color = VaniColors.PanelLit)

        Column(Modifier.weight(1f).padding(start = 14.dp, end = 14.dp, top = 8.dp)) {
            BenchTag(
                tag = "phone 1",
                sub = "speaks",
                color = VaniColors.Ink,
                trailing = {
                    BenchAction("sample in ${d.txLang.label}") { vm.demoSend(vm.demoSample()) }
                }
            )
            Spacer(Modifier.height(7.dp))
            LangDropdown(
                value = d.txLang,
                options = all,
                onPick = { vm.setDemoTxLang(it) },
                enabledFor = { it != d.rxLang },
                noteFor = { if (it == d.rxLang) "phone 2 already hears in this" else null },
            )
            Spacer(Modifier.height(7.dp))
            BenchThread(
                msgs = vm.threadFor(WalkieViewModel.DEMO_TX),
                empty = "Nothing said here yet · hold the mic or type",
                onReplay = { id -> vm.replay(id) },
                modifier = Modifier.weight(1f)
            )
            BenchInput(vm, requestMic)
        }

        WireRow(vm)

        Column(Modifier.weight(1f).padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
            BenchTag(
                tag = "phone 2",
                sub = "hears",
                color = VaniColors.InkDim,
                trailing = { VoicePick(d.rxVoice) { vm.setDemoRxVoice(it) } }
            )
            Spacer(Modifier.height(7.dp))
            LangDropdown(
                value = d.rxLang,
                options = all,
                onPick = { vm.setDemoRxLang(it) },
                enabledFor = { it != d.txLang },
                noteFor = { lg ->
                    when {
                        lg == d.txLang -> "phone 1 already speaks this"
                        lg == Lang.EN -> "no English MT yet · words pass through"
                        else -> null
                    }
                },
            )
            Spacer(Modifier.height(7.dp))
            BenchThread(
                msgs = vm.threadFor(WalkieViewModel.DEMO_RX),
                empty = "Nothing has arrived · phone 1's words land here translated and spoken",
                onReplay = { id -> vm.replay(id) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * A shape beside a word. The design never lets a state ride on colour alone, and the bench's wire
 * row is a state, so the dot is the shape and the text next to it stays the word.
 */
@Composable
private fun Dot(color: Color, ring: Boolean = false) {
    Box(
        Modifier.size(if (ring) 14.dp else 9.dp)
            .then(if (ring) Modifier.border(1.dp, color.copy(alpha = 0.55f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) { Box(Modifier.size(if (ring) 7.dp else 5.dp).background(color, CircleShape)) }
}

// ------------------------------------------------------------------ head and tags

@Composable
private fun BenchHead(vm: WalkieViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "LOOPBACK BENCH",
            style = VaniType.labelMedium, fontWeight = FontWeight.Bold,
            color = VaniColors.InkDim
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "both handsets on this phone",
            style = VaniType.labelSmall, color = VaniColors.InkFaint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        BenchAction("clear") { vm.demoClear() }
    }
}

@Composable
private fun BenchTag(tag: String, sub: String, color: Color, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, ring = true)
        Spacer(Modifier.width(8.dp))
        Text(
            tag.uppercase(),
            style = VaniType.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
            color = color
        )
        Spacer(Modifier.width(9.dp))
        Text(
            sub,
            style = VaniType.labelSmall, color = VaniColors.InkFaint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** A small ruled action. The bench has several, and none of them deserves a full-width button. */
@Composable
private fun BenchAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .background(VaniColors.PanelRaised, RoundedCornerShape(VaniRadiusChip))
            .border(1.dp, VaniColors.Rule, RoundedCornerShape(VaniRadiusChip))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = VaniType.labelSmall, color = VaniColors.InkDim, maxLines = 1)
    }
}

/** Which character answers on phone 2. Both cuts are the same model. */
@Composable
private fun VoicePick(selected: Voice, onPick: (Voice) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("voice", style = VaniType.labelSmall, color = VaniColors.InkFaint)
        Spacer(Modifier.width(6.dp))
        Voice.values().forEach { v ->
            val on = v == selected
            Box(
                Modifier
                    .padding(start = 5.dp)
                    .size(30.dp)
                    .background(if (on) VaniColors.PanelLit else VaniColors.PanelRaised, CircleShape)
                    .border(1.dp, if (on) VaniColors.Ink else VaniColors.Rule, CircleShape)
                    .clickable(role = Role.Button) { onPick(v) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (v == Voice.F) "F" else "M",
                    style = VaniType.labelSmall.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                    color = if (on) VaniColors.Ink else VaniColors.InkDim
                )
            }
        }
    }
}

// ------------------------------------------------------------------ one handset's thread

@Composable
private fun BenchThread(
    msgs: List<Msg>,
    empty: String,
    onReplay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex)
    }
    Box(modifier.fillMaxWidth()) {
        if (msgs.isEmpty()) {
            Text(
                empty,
                style = VaniType.labelSmall, color = VaniColors.InkFaint,
                textAlign = TextAlign.Center, maxLines = 2,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp)
            )
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 2.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(msgs, key = { it.id }) { m ->
                    TurnBubble(m) { onReplay(m.id) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ phone 1's input

/**
 * Typing is the reliable half of the bench: the mic covers what [WalkieViewModel.hasVoice]
 * admits (the languages measured against SraVaani - Odia is not one of them), so any other
 * language on this side has to be typed - and a typed line still runs the whole translate and
 * speak path, which is most of what is being tested here. The mic keeps the console's
 * press-and-hold convention: press opens it, release recognises and puts the words on phone 2.
 */
@Composable
private fun BenchInput(vm: WalkieViewModel, requestMic: () -> Boolean) {
    var draft by rememberSaveable { mutableStateOf("") }
    var onAir by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    val blocked = when {
        !vm.ready() -> "models are still loading"
        !vm.demoMicUsable() ->
            "the mic has no voice model for ${vm.ui.demo.txLang.native} · type it, or use sample"
        else -> null
    }
    val live = onAir && blocked == null
    val now = if (live) Ticker(250L) else 0L
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 7.dp, bottom = 2.dp)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VaniField(
            value = draft,
            onValueChange = { if (it.length <= 300) draft = it },
            placeholder = "type in ${vm.ui.demo.txLang.native}…",
            modifier = Modifier.weight(1f).height(52.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(46.dp)
                .background(VaniColors.Ground, CircleShape)
                .border(1.dp, if (draft.isBlank()) VaniColors.Rule else VaniColors.Ink, CircleShape)
                .clickable(enabled = draft.isNotBlank(), role = Role.Button) {
                    vm.demoSend(draft); draft = ""
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Send, "send from phone 1",
                tint = if (draft.isBlank()) VaniColors.InkFaint else VaniColors.Ink,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(52.dp)
                .background(if (live) VaniColors.Signal else VaniColors.PanelRaised, CircleShape)
                .border(
                    1.dp,
                    when {
                        live -> VaniColors.SignalEdge
                        blocked == null -> VaniColors.Rule
                        else -> VaniColors.PanelLit
                    },
                    CircleShape
                )
                .pointerInput(blocked == null) {
                    awaitEachGesture {
                        if (blocked != null) return@awaitEachGesture
                        awaitFirstDown(requireUnconsumed = false)
                        if (!requestMic()) return@awaitEachGesture
                        startedAt = System.currentTimeMillis()
                        onAir = true
                        vm.demoStartTalk()
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                        onAir = false
                        vm.demoFinishTalk()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Mic, if (live) "on air, release to send" else "hold to talk as phone 1",
                tint = when {
                    live -> VaniColors.OnSignal
                    blocked == null -> VaniColors.Ink
                    else -> VaniColors.InkFaint
                },
                modifier = Modifier.size(22.dp)
            )
        }
    }
    Text(
        when {
            live -> "${((now - startedAt) / 1000L).toInt()}s on air · release sends it to phone 2"
            blocked != null -> blocked
            else -> "hold the mic · how you say it travels with the words"
        },
        style = VaniType.labelSmall,
        color = if (live) VaniColors.Signal else VaniColors.InkFaint,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp)
    )
}

// ------------------------------------------------------------------ the wire between them

/**
 * The seam, and the part worth reading: the pair in flight, what the last turn cost stage by
 * stage, and the swap that turns the bench around so the other direction is tested the same way.
 */
@Composable
private fun WireRow(vm: WalkieViewModel) {
    val d = vm.ui.demo
    val beat = Ticker(320L)
    val pulse = d.running && beat / 640L % 2L == 0L
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .background(VaniColors.Panel)
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(
            when {
                d.running -> VaniColors.Alert
                d.wire.isBlank() -> VaniColors.InkFaint
                else -> VaniColors.Ink
            },
            ring = pulse
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${d.txLang.label} → ${d.rxLang.label} · " +
                    d.toneLabel.ifBlank { "on this phone" },
                style = VaniType.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = VaniColors.InkDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                d.wire.ifBlank { "no turn yet · nothing has crossed" },
                style = VaniType.labelSmall, color = VaniColors.InkFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier
                .size(38.dp)
                .background(VaniColors.PanelRaised, CircleShape)
                .border(1.dp, VaniColors.Rule, CircleShape)
                .clickable(role = Role.Button) { vm.demoHearTone() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.VolumeUp, "hear this line three ways",
                tint = VaniColors.InkDim, modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(38.dp)
                .background(VaniColors.PanelRaised, CircleShape)
                .border(1.dp, VaniColors.Rule, CircleShape)
                .clickable(role = Role.Button) { vm.swapDemo() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.SyncAlt, "run it the other way round",
                tint = VaniColors.InkDim, modifier = Modifier.size(18.dp)
            )
        }
    }
    Rule(color = VaniColors.PanelLit)
}
