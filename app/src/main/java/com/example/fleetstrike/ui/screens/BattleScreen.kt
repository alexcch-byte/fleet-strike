package com.example.fleetstrike.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.example.fleetstrike.game.Coord
import com.example.fleetstrike.game.Orientation
import com.example.fleetstrike.game.ShipType
import com.example.fleetstrike.session.MatchState
import com.example.fleetstrike.session.Phase
import com.example.fleetstrike.session.Verdict
import com.example.fleetstrike.ui.board.BoardView
import com.example.fleetstrike.ui.board.ShipLook
import com.example.fleetstrike.ui.board.ShipOnBoard
import com.example.fleetstrike.ui.board.drawShip
import com.example.fleetstrike.ui.theme.Sea

@Composable
fun BattleScreen(s: MatchState, flash: Flash?, onFire: (Coord) -> Unit, onRematch: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.phase == Phase.GAME_OVER) ResultBanner(s, onRematch, onLeave) else TurnBanner(s, flash)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth > maxHeight
            val reserved = 78.dp // title above each board and fleet status below it
            if (wide) {
                val boardSize = min(maxHeight - reserved, (maxWidth - 32.dp) / 2)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) {
                    MyBoard(s, boardSize)
                    EnemyBoard(s, boardSize, onFire)
                }
            } else {
                val boardSize = min(maxWidth, (maxHeight - reserved * 2 - 16.dp) / 2)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    EnemyBoard(s, boardSize, onFire)
                    MyBoard(s, boardSize)
                }
            }
        }
    }
}

@Composable
private fun MyBoard(s: MatchState, size: Dp) {
    val fleet = s.fleet?.ships?.values.orEmpty()
    BoardColumn(
        title = "Your fleet",
        detail = "${fleet.size - s.mySunk.size} of ${fleet.size} afloat",
        size = size,
        sunk = s.mySunk,
    ) {
        BoardView(
            ships = fleet.map { ShipOnBoard(it, if (it.type in s.mySunk) ShipLook.SUNK else ShipLook.NORMAL) },
            shots = s.shotsAtMe,
            newestShot = s.lastShotAtMe,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun EnemyBoard(s: MatchState, size: Dp, onFire: (Coord) -> Unit) {
    val sunkTypes = s.enemySunk.map { it.type }.toSet()
    val revealed = s.enemyFleet?.ships?.values.orEmpty().filter { it.type !in sunkTypes }
    val hits = s.myShots.count { it.value }
    val accuracy = if (s.myShots.isEmpty()) "" else " · ${hits * 100 / s.myShots.size}%"
    BoardColumn(
        title = "${s.opponentName}'s waters",
        detail = "Shots ${s.myShots.size} · Hits $hits$accuracy",
        size = size,
        sunk = sunkTypes,
    ) {
        BoardView(
            ships = s.enemySunk.map { ShipOnBoard(it, ShipLook.SUNK) } + revealed.map { ShipOnBoard(it, ShipLook.REVEALED) },
            shots = s.myShots,
            newestShot = s.lastMyShot,
            aiming = s.aiming,
            highlight = s.canFire,
            dimmed = s.phase == Phase.BATTLE && !s.myTurn,
            modifier = Modifier.size(size),
            onCellTap = if (s.canFire) onFire else null,
        )
    }
}

@Composable
private fun BoardColumn(title: String, detail: String, size: Dp, sunk: Set<ShipType>, board: @Composable () -> Unit) {
    Column(Modifier.width(size), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, color = Sea.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(detail, color = Sea.TextDim, fontSize = 14.sp, maxLines = 1)
        }
        board()
        FleetTally(sunk)
    }
}

/** The five ship types in miniature, struck out once sunk. */
@Composable
private fun FleetTally(sunk: Set<ShipType>) {
    Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        for (type in ShipType.entries) {
            val down = type in sunk
            Canvas(Modifier.width(16.dp * type.length).height(16.dp)) {
                drawShip(type, Offset.Zero, size.height, Orientation.HORIZONTAL, if (down) ShipLook.SUNK else ShipLook.NORMAL)
                if (down) drawLine(Sea.Hit, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3f)
            }
        }
    }
}

@Composable
private fun TurnBanner(s: MatchState, flash: Flash?) {
    val (text, color) = when {
        s.aiming != null -> "Firing at ${s.aiming.label}…" to Sea.Amber
        s.myTurn -> "Your turn: tap a square in ${s.opponentName}'s waters" to Sea.Sonar
        else -> "${s.opponentName} is taking aim…" to Sea.TextDim
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Sea.Panel, RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        AnimatedVisibility(visible = flash != null, enter = fadeIn(), exit = fadeOut()) {
            if (flash != null) {
                Text(
                    flash.text,
                    color = flash.color,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Sea.Night.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultBanner(s: MatchState, onRematch: () -> Unit, onLeave: () -> Unit) {
    val won = s.won == true
    val accent = if (won) Sea.Sonar else Sea.Hit
    Row(
        Modifier
            .fillMaxWidth()
            .background(Sea.Panel, RoundedCornerShape(16.dp))
            .border(2.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (won) "VICTORY" else "DEFEAT", color = accent, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Column(Modifier.weight(1f)) {
            Text(
                if (won) "You sank ${s.opponentName}'s fleet in ${s.myShots.size} shots."
                else "${s.opponentName} sank your fleet. Their remaining ships are now shown.",
                color = Sea.Text,
                fontSize = 16.sp,
            )
            val (verdict, verdictColor) = when (s.verdict) {
                Verdict.PENDING -> "Checking their fleet against their reports…" to Sea.TextDim
                Verdict.FAIR -> "✓ Fair game: every report matched the fleet they locked in." to Sea.Sonar
                Verdict.SUSPICIOUS -> "⚠ ${s.verdictNote}" to Sea.Danger
            }
            Text(verdict, color = verdictColor, fontSize = 14.sp)
            s.disconnected?.let { Text(it, color = Sea.Amber, fontSize = 14.sp) }
        }
        val rematchLabel = when {
            s.disconnected != null -> "Rematch"
            s.iWantRematch -> "Waiting for ${s.opponentName}…"
            s.opponentWantsRematch -> "Accept rematch"
            else -> "Rematch"
        }
        BigButton(
            rematchLabel,
            onRematch,
            enabled = s.disconnected == null && !s.iWantRematch,
            color = if (s.opponentWantsRematch) Sea.Sonar else Sea.Amber,
        )
        QuietButton("Menu", onLeave)
    }
    if (s.opponentWantsRematch && !s.iWantRematch && s.disconnected == null) {
        Text("${s.opponentName} wants a rematch!", color = Sea.Sonar, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
