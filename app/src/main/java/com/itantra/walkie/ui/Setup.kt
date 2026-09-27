package com.itantra.walkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.itantra.walkie.AppConfig
import com.itantra.walkie.Lang
import com.itantra.walkie.Voice
import com.itantra.walkie.WalkieViewModel

/**
 * Setup: who this phone is on the mesh, and in which two languages that work happens.
 *
 * Ported from `vani-setup.html`. The export's "Speech pack" switch is the one control here that
 * the real app cannot honour: the recognition model is bundled in the APK, not fetched, so a
 * toggle would either do nothing or lie about what the phone can hear. It is a readout instead -
 * the same facts, in the same place, with the numbers this build actually measured.
 *
 * Everything the screen claims about capability comes from [WalkieViewModel.hasVoice], which is
 * the measured set, so a language whose mic was never tested reads as typed rather than as a dead
 * microphone the operator discovers in the field.
 */
@Composable
fun SetupPanel(vm: WalkieViewModel, firstRun: Boolean, onDone: () -> Unit) {
    var name by remember { mutableStateOf(vm.ui.name) }
    val canJoin = name.isNotBlank()

    Column(
        Modifier.fillMaxSize()
            .background(VaniColors.Ground)
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(bottom = if (firstRun) 20.dp else NavClearance)
        ) {
            AppTop(
                title = "Setup",
                sub = if (firstRun) "Spoken once — every phone in range reads it"
                else "Who this phone is to the mesh",
            )
            Column(Modifier.screenGutter()) {
                Group("Display name") {
                    VaniCard {
                        Column {
                            Text(
                                "This is what every other phone sees in their scan.",
                                style = VaniType.bodySmall, color = VaniColors.InkDim
                            )
                            Spacer(Modifier.height(10.dp))
                            VaniField(
                                value = name,
                                onValueChange = { if (it.length <= 20) name = it },
                                placeholder = "e.g. Ananya",
                                maxLength = 20,
                            )
                            Spacer(Modifier.height(10.dp))
                            CapLine("Others will see: ", name.trim().ifBlank { "(name not set)" })
                        }
                    }
                }

                Group("Speech pack") {
                    VaniCard {
                        Column {
                            Readout("Recognition model", "SraVaani-1.0 · int8 · bundled in the app")
                            Readout(
                                "Languages the mic is measured on",
                                "${Lang.values().count { vm.hasVoice(it) }} of ${Lang.values().size}"
                            )
                            Readout("Pack size on this phone", "520 MB, inside the 970 MB app")
                            Spacer(Modifier.height(8.dp))
                            Rule()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Nothing is fetched at runtime: the model ships in the app so the " +
                                    "phone keeps working with no network, which is the point of it.",
                                style = VaniType.labelSmall, color = VaniColors.InkFaint
                            )
                        }
                    }
                }

                Group("Your voice") {
                    VaniCard {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Voice.values().forEach { v ->
                                    VoiceOption(
                                        label = if (v == Voice.F) "Female" else "Male",
                                        selected = vm.ui.voice == v,
                                        modifier = Modifier.weight(1f),
                                    ) { vm.setVoice(v) }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Rule()
                            Spacer(Modifier.height(10.dp))
                            SwitchRow(
                                "Tone matching",
                                "Reply is rendered in the shade the caller used · measured, not felt",
                                vm.ui.matchTone
                            ) { vm.setMatchTone(it) }
                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                "Male voice trim",
                                "Walks the brighter solve back toward the pitch of its own prompt",
                                vm.ui.softMale
                            ) { vm.setSoftMale(it) }
                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                "Translate every transmission",
                                "Off sends your own words across unchanged",
                                vm.ui.translateOn
                            ) { vm.setTranslateEnabled(it) }
                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                "Turbo solve",
                                if (vm.ui.turbo) "Shorter donor prompt · ${AppConfig.TTS_NFE_TURBO} flow steps · answers in ~3 s"
                                else "Whole donor prompt · ${AppConfig.TTS_NFE_FULL} flow steps · the fullest voice",
                                vm.ui.turbo
                            ) { vm.setTurbo(it) }
                            Spacer(Modifier.height(14.dp))
                            PrimaryButton(
                                label = "Test voice on this phone",
                                icon = VaniIcons.Speak,
                                onClick = { vm.testVoice() }
                            )
                            if (vm.ui.lastText.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    vm.ui.status, style = VaniType.labelSmall, color = VaniColors.InkFaint
                                )
                            }
                        }
                    }
                }

                Group("What to expect") {
                    Notice(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append("Every language is loaded in the app itself. ")
                            append(
                                "A transmission is recognised in the sender's own language and turned " +
                                    "into this phone's, so nobody picks a language per message. "
                            )
                            append("Translation is Indic ⇄ Indic. ")
                            append(
                                "English carries your words through unchanged and says so on the " +
                                    "bubble. The male cut has its own reference voice in all eleven " +
                                    "languages; the female cut is still donor-backed for मराठी, " +
                                    "ગુજરાતી, తెలుగు, ಕನ್ನಡ, മലയാളം, বাংলা and ଓଡ଼ିଆ, so there it " +
                                    "borrows a neighbouring speaker."
                            )
                        }
                    )
                }

                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    label = if (firstRun) "Save — go find people" else "Save identity",
                    enabled = canJoin,
                ) {
                    vm.completeSetup(name, vm.ui.spoken.ifEmpty { listOf(Lang.HI, Lang.EN) }, vm.ui.src, vm.ui.tgt)
                    onDone()
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** `.cap` with one bold clause - the design's way of labelling a live value. */
@Composable
private fun CapLine(lead: String, value: String) {
    Row {
        Text(lead, style = VaniType.labelSmall, color = VaniColors.InkFaint)
        Text(value, style = VaniType.labelSmall, color = VaniColors.Ink, fontWeight = FontWeight.Bold)
    }
}

/**
 * A fact the app knows about itself, in the `.switch-row` shape but without the control: the label
 * in the body face, the measured value under it in mono. Side by side, a long value squeezed the
 * label into three lines and the two collided.
 */
@Composable
private fun Readout(title: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = VaniType.bodyMedium, color = VaniColors.Ink)
        Text(value, style = VaniType.labelMedium, color = VaniColors.InkDim)
    }
}

/** `.lang-opt` for two choices side by side, where a badge would only add noise. */
@Composable
private fun VoiceOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxWidth().height(VaniTarget)
            .background(if (selected) VaniColors.PanelLit else VaniColors.Panel, VaniShapes.small)
            .border(
                1.dp, if (selected) VaniColors.Ink else VaniColors.Rule, VaniShapes.small
            )
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = VaniType.titleMedium, color = VaniColors.Ink)
    }
}
