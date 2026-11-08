package com.example.fleetstrike.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.fleetstrike.audio.Sfx
import com.example.fleetstrike.session.MatchEvent
import com.example.fleetstrike.session.MatchState
import com.example.fleetstrike.session.Phase
import com.example.fleetstrike.ui.AppViewModel
import com.example.fleetstrike.ui.Screen
import com.example.fleetstrike.ui.theme.Sea
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A short message that pops up after a shot ("You sank their Cruiser!"). */
data class Flash(val text: String, val color: Color, val id: Long = System.nanoTime())

@Composable
fun MatchScreen(vm: AppViewModel, match: Screen.Match) {
    val session = match.session
    val s by session.state.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    val haptics = LocalHapticFeedback.current

    BackHandler { if (s.disconnected != null || s.phase == Phase.GAME_OVER) vm.leaveMatch() else confirmLeave = true }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(session) {
        session.events.collect { e ->
            val opponent = session.state.value.opponentName
            when (e) {
                is MatchEvent.BattleStarted -> flash = Flash(if (e.myTurn) "Battle stations! You fire first." else "$opponent fires first.", Sea.Amber)
                is MatchEvent.Fired -> vm.sound.play(Sfx.LAUNCH, 0.7f)
                is MatchEvent.OurShot -> {
                    flash = when {
                        e.won -> Flash("You sank their whole fleet!", Sea.Sonar)
                        e.sunk != null -> Flash("You sank their ${e.sunk.title}!", Sea.Sonar)
                        e.hit -> Flash("Hit at ${e.at.label}!", Sea.Flame)
                        else -> Flash("Miss at ${e.at.label}.", Sea.TextDim)
                    }
                    // Let the shell land on the board before the bang.
                    launch {
                        delay(280)
                        vm.sound.play(if (e.sunk != null) Sfx.SINK else if (e.hit) Sfx.BOOM else Sfx.SPLASH)
                        if (e.won) {
                            delay(900)
                            vm.sound.play(Sfx.WIN)
                        }
                    }
                }
                is MatchEvent.TheirShot -> {
                    flash = when {
                        e.lost -> Flash("$opponent sank your last ship.", Sea.Danger)
                        e.sunk != null -> Flash("$opponent sank your ${e.sunk.title}!", Sea.Danger)
                        e.hit -> Flash("$opponent hit you at ${e.at.label}!", Sea.Hit)
                        else -> Flash("$opponent missed at ${e.at.label}.", Sea.TextDim)
                    }
                    if (e.hit) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    launch {
                        vm.sound.play(Sfx.LAUNCH, 0.5f)
                        delay(280)
                        vm.sound.play(if (e.sunk != null) Sfx.SINK else if (e.hit) Sfx.BOOM else Sfx.SPLASH)
                        if (e.lost) {
                            delay(900)
                            vm.sound.play(Sfx.LOSE)
                        }
                    }
                }
                MatchEvent.RematchStarted -> flash = Flash("New game! Deploy your fleet.", Sea.Amber)
            }
        }
    }
    LaunchedEffect(flash?.id) {
        if (flash != null) {
            delay(2600)
            flash = null
        }
    }

    SeaBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fit = fitFor(maxWidth, maxHeight)
            val roomy = fit == Fit.ROOMY
            Column(
                Modifier.fillMaxSize().padding(horizontal = if (roomy) 20.dp else 10.dp, vertical = if (roomy) 12.dp else 6.dp),
                verticalArrangement = Arrangement.spacedBy(if (roomy) 12.dp else 8.dp),
            ) {
                TopBar(s, match.via, vm.soundOn, vm::updateSound, compact = !roomy, onLeave = {
                    if (s.disconnected != null || s.phase == Phase.GAME_OVER) vm.leaveMatch() else confirmLeave = true
                })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (s.phase) {
                        Phase.HANDSHAKE -> Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Sea.Amber)
                            Spacer(Modifier.width(16.dp))
                            Text("Connected. Saying hello to the other device…", color = Sea.Text, fontSize = if (roomy) 20.sp else 16.sp)
                        }
                        Phase.PLACEMENT -> PlacementScreen(s, session, vm.sound, compact = !roomy)
                        Phase.BATTLE, Phase.GAME_OVER -> BattleScreen(
                            s = s,
                            flash = flash,
                            fit = fit,
                            onFire = { session.fire(it) },
                            onRematch = session::requestRematch,
                            onLeave = vm::leaveMatch,
                        )
                    }
                }
            }
        }

        if (confirmLeave) {
            AlertDialog(
                onDismissRequest = { confirmLeave = false },
                title = { Text("Leave the game?") },
                text = { Text("${s.opponentName.ifEmpty { "The other player" }} will be told you left.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLeave = false
                        vm.leaveMatch()
                    }) { Text("Leave") }
                },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep playing") } },
            )
        }

        // After the game is over the result banner says so instead, so the boards stay visible.
        val reason = s.disconnected
        if (reason != null && s.phase != Phase.GAME_OVER) {
            AlertDialog(
                onDismissRequest = vm::leaveMatch,
                title = { Text("Game ended") },
                text = { Text(reason) },
                confirmButton = { TextButton(onClick = vm::leaveMatch) { Text("Back to menu") } },
            )
        }
    }
}

/**
 * Score, sound and leave. On a tablet it also shows the game's name and how the tablets are linked; on a phone only
 * the essentials stay, and long player names are shortened rather than pushing the buttons off screen.
 */
@Composable
private fun TopBar(s: MatchState, via: String, soundOn: Boolean, onSound: (Boolean) -> Unit, compact: Boolean, onLeave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
        if (compact) {
            ScoreChip(s, compact = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSound(!soundOn) }) { Text(if (soundOn) "🔊" else "🔇", fontSize = 18.sp) }
            QuietButton("Leave", onLeave, small = true)
        } else {
            Text("FLEET STRIKE", color = Sea.Amber, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            ScoreChip(s, compact = false)
            Spacer(Modifier.weight(1f))
            Text(via, color = Sea.TextDim, fontSize = 14.sp, maxLines = 1)
            TextButton(onClick = { onSound(!soundOn) }) { Text(if (soundOn) "Sound on" else "Sound off", color = Sea.TextDim, maxLines = 1) }
            QuietButton("Leave", onLeave)
        }
    }
}

@Composable
private fun ScoreChip(s: MatchState, compact: Boolean, modifier: Modifier = Modifier) {
    if (s.opponentName.isEmpty()) {
        Spacer(modifier)
        return
    }
    Row(
        modifier.background(Sea.Panel, RoundedCornerShape(50)).padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp, Alignment.CenterHorizontally),
    ) {
        val nameSize = if (compact) 14.sp else 16.sp
        val names = if (compact) Modifier.weight(1f, fill = false) else Modifier
        Text(s.myName, color = Sea.Text, fontSize = nameSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = names)
        Text("${s.wins} : ${s.losses}", color = Sea.Amber, fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(s.opponentName, color = Sea.Text, fontSize = nameSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = names)
    }
}
