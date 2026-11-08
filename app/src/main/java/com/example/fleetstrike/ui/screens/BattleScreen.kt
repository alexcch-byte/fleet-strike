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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** Height each board needs on top of its squares: the title above and the fleet tally below. */
private fun boardExtras(compact: Boolean): Dp = if (compact) 50.dp else 78.dp

@Composable
fun BattleScreen(s: MatchState, flash: Flash?, fit: Fit, onFire: (Coord) -> Unit, onRematch: () -> Unit, onLeave: () -> Unit) {
    val over = s.phase == Phase.GAME_OVER
    when (fit) {
        Fit.ROOMY -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (over) ResultBanner(s, onRematch, onLeave) else TurnBanner(s, flash)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth > maxHeight) {
                    val board = min(maxHeight - boardExtras(false), (maxWidth - 32.dp) / 2)
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) {
                        MyBoard(s, board, compact = false)
                        EnemyBoard(s, board, onFire, compact = false)
                    }
                } else {
                    StackedBoards(s, onFire, compact = false)
                }
            }
        }

        Fit.NARROW -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (over) CompactResult(s, onRematch, onLeave) else CompactTurn(s, flash, Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) { StackedBoards(s, onFire, compact = true) }
        }

        // A phone on its side: both boards as tall as the screen allows, with the status in a column beside them.
        Fit.SHORT -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = (maxWidth * 0.26f).coerceIn(180.dp, 280.dp)
            val board = min(maxHeight - boardExtras(true), (maxWidth - side - 24.dp) / 2)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MyBoard(s, board, compact = true)
                EnemyBoard(s, board, onFire, compact = true)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (over) CompactResult(s, onRematch, onLeave) else CompactTurn(s, flash, Modifier.fillMaxWidth(), wrap = true)
                }
            }
        }
    }
}

/**
 * Upright screens: the board you fire at goes on top and gets most of the room, since that's where you tap. Your own
 * fleet sits underneath, smaller.
 */
@Composable
private fun StackedBoards(s: MatchState, onFire: (Coord) -> Unit, compact: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = if (compact) 10.dp else 16.dp
        val room = maxHeight - boardExtras(compact) * 2 - gap
        val enemy = min(maxWidth, room * 0.58f)
        val mine = min(maxWidth, room - enemy)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            EnemyBoard(s, enemy, onFire, compact)
            MyBoard(s, mine, compact)
        }
    }
}

@Composable
private fun MyBoard(s: MatchState, size: Dp, compact: Boolean) {
    val fleet = s.fleet?.ships?.values.orEmpty()
    BoardColumn(
        title = "Your fleet",
        detail = "${fleet.size - s.mySunk.size} of ${fleet.size} afloat",
        size = size,
        sunk = s.mySunk,
        compact = compact,
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
private fun EnemyBoard(s: MatchState, size: Dp, onFire: (Coord) -> Unit, compact: Boolean) {
    val sunkTypes = s.enemySunk.map { it.type }.toSet()
    val revealed = s.enemyFleet?.ships?.values.orEmpty().filter { it.type !in sunkTypes }
    val hits = s.myShots.count { it.value }
    val accuracy = if (s.myShots.isEmpty()) "" else " · ${hits * 100 / s.myShots.size}%"
    BoardColumn(
        title = "${s.opponentName}'s waters",
        detail = if (compact) "$hits/${s.myShots.size} hits$accuracy" else "Shots ${s.myShots.size} · Hits $hits$accuracy",
        size = size,
        sunk = sunkTypes,
        compact = compact,
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
private fun BoardColumn(title: String, detail: String, size: Dp, sunk: Set<ShipType>, compact: Boolean, board: @Composable () -> Unit) {
    Column(Modifier.width(size), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                color = Sea.Text,
                fontSize = if (compact) 15.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(detail, color = Sea.TextDim, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1)
        }
        board()
        FleetTally(sunk, width = size, compact = compact)
    }
}

/** The five ship types in miniature, struck out once sunk. Shrinks to fit narrow boards. */
@Composable
private fun FleetTally(sunk: Set<ShipType>, width: Dp, compact: Boolean) {
    val cell = min(if (compact) 12.dp else 16.dp, width / 19)
    Row(
        Modifier.fillMaxWidth().height(if (compact) 18.dp else 26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (type in ShipType.entries) {
            val down = type in sunk
            Canvas(Modifier.width(cell * type.length).height(cell)) {
                drawShip(type, Offset.Zero, size.height, Orientation.HORIZONTAL, if (down) ShipLook.SUNK else ShipLook.NORMAL)
                if (down) drawLine(Sea.Hit, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3f)
            }
        }
    }
}

private fun turnText(s: MatchState, short: Boolean): Pair<String, Color> = when {
    s.aiming != null -> "Firing at ${s.aiming.label}…" to Sea.Amber
    s.myTurn -> (if (short) "Your turn: pick a target" else "Your turn: tap a square in ${s.opponentName}'s waters") to Sea.Sonar
    else -> "${s.opponentName} is taking aim…" to Sea.TextDim
}

@Composable
private fun TurnBanner(s: MatchState, flash: Flash?) {
    val (text, color) = turnText(s, short = false)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Sea.Panel, RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.weight(1f))
        AnimatedVisibility(visible = flash != null, enter = fadeIn(), exit = fadeOut()) {
            if (flash != null) {
                Text(
                    flash.text,
                    color = flash.color,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.background(Sea.Night.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The phone version: whose turn it is, with the latest shot's news on the line below. Upright the box keeps a fixed
 * height so the boards don't jump when a message appears; on its side ([wrap]) the text may take as many lines as it needs.
 */
@Composable
private fun CompactTurn(s: MatchState, flash: Flash?, modifier: Modifier, wrap: Boolean = false) {
    val (text, color) = turnText(s, short = true)
    Column(
        modifier
            .then(if (wrap) Modifier else Modifier.height(64.dp))
            .background(Sea.Panel, RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Text(
            text,
            color = color,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = if (wrap) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            flash?.text ?: "",
            color = flash?.color ?: Sea.TextDim,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (wrap) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun rematchLabel(s: MatchState) = when {
    s.disconnected != null -> "Rematch"
    s.iWantRematch -> "Waiting for ${s.opponentName}…"
    s.opponentWantsRematch -> "Accept rematch"
    else -> "Rematch"
}

@Composable
private fun VerdictLine(s: MatchState, fontSize: Int) {
    val (verdict, color) = when (s.verdict) {
        Verdict.PENDING -> "Checking their fleet against their reports…" to Sea.TextDim
        Verdict.FAIR -> "✓ Fair game: every report matched the fleet they locked in." to Sea.Sonar
        Verdict.SUSPICIOUS -> "⚠ ${s.verdictNote}" to Sea.Danger
    }
    Text(verdict, color = color, fontSize = fontSize.sp)
    s.disconnected?.let { Text(it, color = Sea.Amber, fontSize = fontSize.sp) }
}

private fun summary(s: MatchState) =
    if (s.won == true) "You sank ${s.opponentName}'s fleet in ${s.myShots.size} shots."
    else "${s.opponentName} sank your fleet. Their remaining ships are now shown."

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
            Text(summary(s), color = Sea.Text, fontSize = 16.sp)
            VerdictLine(s, fontSize = 14)
        }
        BigButton(
            rematchLabel(s),
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

/** The phone version of the result: everything stacked, buttons along the bottom. */
@Composable
private fun CompactResult(s: MatchState, onRematch: () -> Unit, onLeave: () -> Unit) {
    val won = s.won == true
    val accent = if (won) Sea.Sonar else Sea.Hit
    Column(
        Modifier
            .fillMaxWidth()
            .background(Sea.Panel, RoundedCornerShape(14.dp))
            .border(2.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (won) "VICTORY" else "DEFEAT", color = accent, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Text(summary(s), color = Sea.Text, fontSize = 14.sp)
        VerdictLine(s, fontSize = 13)
        if (s.opponentWantsRematch && !s.iWantRematch && s.disconnected == null) {
            Text("${s.opponentName} wants a rematch!", color = Sea.Sonar, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton(
                rematchLabel(s),
                onRematch,
                Modifier.weight(1f),
                enabled = s.disconnected == null && !s.iWantRematch,
                color = if (s.opponentWantsRematch) Sea.Sonar else Sea.Amber,
                small = true,
            )
            QuietButton("Menu", onLeave, small = true)
        }
    }
}
