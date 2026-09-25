package com.itantra.walkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.walkie.Lang
import com.itantra.walkie.Voice
import com.itantra.walkie.WalkieViewModel

/**
 * The one question this screen has to answer is: who are you on the mesh, and in which two
 * languages does that work. Name first, because it is what the other phones see; then what you
 * say, then what you want back.
 *
 * The voice input line is the honest constraint: recognition exists for Hindi only, so a
 * language without it is offered as typed input rather than as a dead microphone.
 */
@Composable
fun SetupPanel(vm: WalkieViewModel, firstRun: Boolean, onDone: () -> Unit) {
    val all = Lang.values().toList()
    var name by remember { mutableStateOf(vm.ui.name) }
    var spoken by remember { mutableStateOf(vm.ui.spoken.ifEmpty { listOf(Lang.HI) }) }
    var micIn by remember { mutableStateOf(if (vm.ui.src in all) vm.ui.src else Lang.HI) }
    var hearIn by remember { mutableStateOf(vm.ui.tgt) }

    val canJoin = name.isNotBlank() && spoken.isNotEmpty() && micIn in spoken && hearIn != micIn

    Column(
        Modifier
            .fillMaxSize()
            .background(VaniColors.Ground)
    ) {
        // The wordmark opens the phone before it has a name. Inside the console the strip already
        // carries it, so the identity pane does not repeat it.
        if (firstRun) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                VaniWordmark(health = 0.12f, modifier = Modifier.width(104.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    "Set up once · every phone in range reads it",
                    style = VaniType.labelMedium, color = VaniColors.InkFaint, maxLines = 2
                )
            }
            Rule()
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
        ) {
            if (firstRun) {
                Text(
                    "Nothing here goes over a network. Your phone is one radio among the others in " +
                        "range, and every translation happens inside it.",
                    style = VaniType.bodyMedium, color = VaniColors.InkDim,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                )
            }

            Section("Name on the mesh", "This is what every other phone shows in its scan.") {
                ConsoleField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    placeholder = "e.g. Asha · Rampur 12",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Section("Languages you speak", "Pick every one you can hold a conversation in.") {
                LangGrid(
                    langs = all,
                    selected = spoken,
                    subFor = { if (it == Lang.HI) "${it.label} · voice" else "${it.label} · typed" },
                    onPick = { lg ->
                        spoken = if (spoken.any { it == lg }) {
                            (spoken - lg).toList().ifEmpty { listOf(lg) }
                        } else spoken + lg
                        if (micIn !in spoken) micIn = spoken.first()
                        if (hearIn in spoken && hearIn == micIn) hearIn = (all - micIn).first()
                    }
                )
            }

            Section(
                "Speak into the mic in",
                "Speech recognition ships for हिन्दी only. Any other language you type, and it is " +
                    "still spoken back to you."
            ) {
                LangGrid(
                    langs = spoken,
                    selected = listOf(micIn),
                    subFor = { if (it == Lang.HI) null else "typed input" },
                    onPick = { micIn = it }
                )
            }

            Section(
                "Read and hear replies in",
                "Your phone turns every incoming transmission into this language locally, whatever " +
                    "it arrived as."
            ) {
                LangGrid(
                    langs = all,
                    selected = listOf(hearIn),
                    enabled = { it != micIn },
                    subFor = { if (it == Lang.EN) "no EN translation yet" else null },
                    onPick = { hearIn = it }
                )
            }

            if (!firstRun) {
                Section("Voice that answers", "Both cuts are the same model; the character differs.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Voice.values().forEach { v ->
                            PanelOption(
                                label = if (v == Voice.F) "Female" else "Male",
                                sub = null,
                                selected = vm.ui.voice == v,
                                enabled = true,
                                modifier = Modifier.weight(1f),
                                onClick = { vm.setVoice(v) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton("Test voice on this phone") { vm.testVoice() }
                }
                Rule(color = VaniColors.PanelLit)
                ToggleRow(
                    label = "Translate every transmission",
                    sub = "Off sends your own words across unchanged.",
                    checked = vm.ui.translateOn,
                    onChange = { vm.setTranslateEnabled(it) }
                )
                Rule(color = VaniColors.PanelLit)
                ToggleRow(
                    label = "Soften the male voice",
                    sub = "Off answers at the raw pitch and brightness the model solved, which " +
                        "sits higher and buzzier than the voice it was cloned from.",
                    checked = vm.ui.softMale,
                    onChange = { vm.setSoftMale(it) }
                )
                Rule(color = VaniColors.PanelLit)
                ToggleRow(
                    label = "Speak back in the caller's tone",
                    sub = "Off always answers in the flat, tuned reading the voice was built with.",
                    checked = vm.ui.matchTone,
                    onChange = { vm.setMatchTone(it) }
                )
                Rule(color = VaniColors.PanelLit)
                ToggleRow(
                    label = "Turbo solve",
                    sub = "Fewer flow steps: faster, less settled.",
                    checked = vm.ui.turbo,
                    onChange = { vm.setTurbo(it) }
                )
                Rule(color = VaniColors.PanelLit)
                // Inside the console the action belongs to the form it commits, not to a bar that
                // would stack on top of the readout, the talk bar and the navigation.
                ConsoleButton(
                    label = "Save identity",
                    enabled = canJoin,
                    modifier = Modifier.padding(top = 18.dp)
                ) {
                    vm.completeSetup(name, spoken, micIn, hearIn)
                    onDone()
                }
            }
        }

        if (firstRun) {
            // On the way in the action never scrolls away: it is the door into the mesh.
            Column(Modifier.fillMaxWidth().background(VaniColors.Panel)) {
                Rule()
                ConsoleButton(
                    label = "Join the mesh",
                    enabled = canJoin,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    vm.completeSetup(name, spoken, micIn, hearIn)
                    onDone()
                }
            }
        }
    }
}

/**
 * A labelled block of the panel: caps label, one line of why it matters, the controls, then the
 * hairline that closes it. Every section is the same shape so the eye learns the rhythm once.
 */
@Composable
private fun Section(title: String, note: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 18.dp)) {
        FieldLabel(title)
        Spacer(Modifier.height(6.dp))
        Text(note, style = VaniType.bodySmall, color = VaniColors.InkDim)
        Spacer(Modifier.height(14.dp))
        content()
    }
    Rule(color = VaniColors.PanelLit)
}

/**
 * The wordmark with the mesh's own health under it: the bar is how much of the roster is
 * answering, so the header is a readout rather than decoration.
 */
@Composable
fun VaniWordmark(health: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "VANI",
            style = VaniType.headlineMedium.copy(
                fontFamily = VaniConsoleFamily, letterSpacing = 3.sp
            ),
            color = VaniColors.Ink
        )
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth().height(2.dp).background(VaniColors.PanelLit)) {
            Spacer(Modifier.fillMaxWidth(health.coerceIn(0.08f, 1f)).background(VaniColors.Signal))
        }
    }
}
