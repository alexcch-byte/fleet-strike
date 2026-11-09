package com.example.fleetstrike.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fleetstrike.game.Difficulty
import com.example.fleetstrike.ui.AppViewModel
import com.example.fleetstrike.ui.Rotation
import com.example.fleetstrike.ui.Transport
import com.example.fleetstrike.ui.theme.Sea
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(vm: AppViewModel) {
    SeaBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight && maxWidth > 700.dp
            val compact = maxWidth < 480.dp || maxHeight < 560.dp
            if (wide) {
                Row(
                    Modifier.fillMaxSize().padding(28.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Masthead(compact)
                        CaptainPanel(vm)
                    }
                    Column(
                        Modifier.weight(1.15f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PlayPanels(vm, compact = false)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (compact) 16.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Masthead(compact)
                    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CaptainPanel(vm)
                        PlayPanels(vm, compact)
                    }
                }
            }
        }
    }
}

@Composable
private fun Masthead(compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Radar(Modifier.size(if (compact) 120.dp else 190.dp))
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Text(
            "FLEET STRIKE",
            color = Sea.Text,
            fontSize = if (compact) 32.sp else 44.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = if (compact) 3.sp else 6.sp,
            maxLines = 1,
        )
        Text("Hide your fleet. Hunt theirs.", color = Sea.TextDim, fontSize = if (compact) 15.sp else 17.sp)
    }
}

@Composable
private fun CaptainPanel(vm: AppViewModel) {
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("Captain")
        OutlinedTextField(
            value = vm.playerName,
            onValueChange = vm::updateName,
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingRow("Extra shot after a hit", "House rule for games you host", vm.bonusShot, vm::updateBonusShot)
        SettingRow("Sound", null, vm.soundOn, vm::updateSound)
        Column {
            Text("Screen", color = Sea.Text, fontSize = 16.sp)
            Hint("Auto turns with the device, even with rotation lock on")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (r in Rotation.entries) Choice(r.title, vm.rotation == r) { vm.updateRotation(r) }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp)) },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Sea.PanelHigh, selectedLabelColor = Sea.Amber),
    )
}

@Composable
private fun SettingRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Sea.Text, fontSize = 16.sp)
            if (detail != null) Hint(detail)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PlayPanels(vm: AppViewModel, compact: Boolean) {
    Panel(Modifier.fillMaxWidth(), accent = Sea.Sonar) {
        PanelTitle("Play over Wi-Fi")
        Hint("Both devices on the same Wi-Fi network (or one sharing a hotspot with the other). One hosts, the other joins.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Host game", { vm.openLobby(Transport.WIFI, hosting = true) }, Modifier.weight(1f))
            BigButton("Join game", { vm.openLobby(Transport.WIFI, hosting = false) }, Modifier.weight(1f), color = Sea.Sonar)
        }
    }
    Panel(Modifier.fillMaxWidth(), accent = Color(0xFF6FA8FF)) {
        PanelTitle("Play over Bluetooth")
        Hint("No Wi-Fi needed. Keep the devices within a few metres of each other.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Host game", { vm.openLobby(Transport.BLUETOOTH, hosting = true) }, Modifier.weight(1f))
            BigButton("Join game", { vm.openLobby(Transport.BLUETOOTH, hosting = false) }, Modifier.weight(1f), color = Sea.Sonar)
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("Practice vs computer")
        val chips = @Composable {
            for (d in Difficulty.entries) Choice(d.title, vm.difficulty == d) { vm.updateDifficulty(d) }
        }
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { chips() }
            BigButton("Start", vm::startPractice, Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                chips()
                Spacer(Modifier.weight(1f))
                BigButton("Start", vm::startPractice)
            }
        }
    }
}

/** A sonar screen: range rings and a sweeping beam that lights up blips as it passes. */
@Composable
private fun Radar(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "sweep")
    val blips = listOf(40f to 0.55f, 155f to 0.75f, 250f to 0.35f, 310f to 0.82f)
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(Color(0xFF0A2A22), radius = r, center = c)
        for (i in 1..4) drawCircle(Sea.Sonar.copy(alpha = 0.28f), radius = r * i / 4f, center = c, style = Stroke(1.5f))
        drawLine(Sea.Sonar.copy(alpha = 0.25f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
        drawLine(Sea.Sonar.copy(alpha = 0.25f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
        rotate(angle, pivot = c) {
            drawCircle(
                Brush.sweepGradient(
                    0.0f to Color.Transparent,
                    0.82f to Color.Transparent,
                    1.0f to Sea.Sonar.copy(alpha = 0.55f),
                    center = c,
                ),
                radius = r,
                center = c,
            )
            drawLine(Sea.Sonar, c, Offset(c.x + r, c.y), 2.5f)
        }
        for ((deg, dist) in blips) {
            // Brightest just after the beam has passed over the blip, fading as it moves on.
            val since = ((angle - deg) % 360f + 360f) % 360f
            val glow = (1f - since / 300f).coerceIn(0f, 1f)
            val rad = deg * PI.toFloat() / 180f
            val p = Offset(c.x + cos(rad) * r * dist, c.y + sin(rad) * r * dist)
            drawCircle(Sea.Sonar.copy(alpha = 0.15f + 0.85f * glow), radius = r * 0.045f, center = p)
        }
        drawCircle(Sea.Sonar.copy(alpha = 0.7f), radius = r, center = c, style = Stroke(3f))
    }
}
