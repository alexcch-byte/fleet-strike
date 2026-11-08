package com.example.fleetstrike.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.example.fleetstrike.audio.Sfx
import com.example.fleetstrike.audio.SoundFx
import com.example.fleetstrike.game.Coord
import com.example.fleetstrike.game.Fleet
import com.example.fleetstrike.game.GRID
import com.example.fleetstrike.game.Orientation
import com.example.fleetstrike.game.Placement
import com.example.fleetstrike.game.ShipType
import com.example.fleetstrike.session.MatchSession
import com.example.fleetstrike.session.MatchState
import com.example.fleetstrike.ui.board.BoardGeometry
import com.example.fleetstrike.ui.board.BoardView
import com.example.fleetstrike.ui.board.ShipLook
import com.example.fleetstrike.ui.board.ShipOnBoard
import com.example.fleetstrike.ui.board.drawShip
import com.example.fleetstrike.ui.theme.Sea
import kotlin.math.floor
import kotlin.random.Random

/** A ship being dragged: from the tray ([from] null) or picked up off the board. [grab] is which square the finger holds. */
private class DragState(val type: ShipType, val orientation: Orientation, val grab: Int, val from: Placement?, start: Offset) {
    var pointer by mutableStateOf(start)
}

/** The fleet being laid out and the drag in progress. Positions are in window ("root") coordinates. */
@Stable
private class Deployment {
    var fleet by mutableStateOf(Fleet())
    var drag by mutableStateOf<DragState?>(null)
    var board by mutableStateOf<BoardGeometry?>(null)

    fun startFromTray(type: ShipType, grab: Int, rootPos: Offset) {
        drag = DragState(type, Orientation.HORIZONTAL, grab, null, rootPos)
    }

    fun pickUpAt(local: Offset) {
        val g = board ?: return
        val cell = g.cellAtLocal(local) ?: return
        val p = fleet.at(cell) ?: return
        fleet = fleet.without(p.type)
        drag = DragState(p.type, p.orientation, p.cells.indexOf(cell), p, g.boardInRoot + local)
    }

    fun moveBy(delta: Offset) {
        drag?.let { it.pointer += delta }
    }

    /** Where the dragged ship would land, or null when the finger isn't over the grid. */
    fun target(d: DragState): Placement? {
        val cell = board?.cellAtRoot(d.pointer) ?: return null
        val origin = if (d.orientation == Orientation.HORIZONTAL) Coord(cell.row, cell.col - d.grab) else Coord(cell.row - d.grab, cell.col)
        return Placement(d.type, origin, d.orientation)
    }

    /** True if the ship found a new home on the grid. */
    fun drop(): Boolean {
        val d = drag ?: return false
        drag = null
        val t = target(d)
        return when {
            t != null && fleet.canPlace(t) -> {
                fleet = fleet.with(t)
                true
            }
            // Let go over the grid where it doesn't fit: back to where it was. Let go off the grid: back to the tray.
            t != null && d.from != null -> {
                fleet = fleet.with(d.from)
                false
            }
            else -> false
        }
    }

    fun cancel() {
        val d = drag ?: return
        drag = null
        if (d.from != null) fleet = fleet.with(d.from)
    }

    fun rotateAt(c: Coord): Boolean {
        val p = fleet.at(c) ?: return false
        val turned = fleet.rotated(p.type) ?: return false
        fleet = fleet.with(turned)
        return true
    }

    fun placeAnywhere(type: ShipType) {
        repeat(500) {
            val p = Placement(
                type,
                Coord(Random.nextInt(GRID), Random.nextInt(GRID)),
                if (Random.nextBoolean()) Orientation.HORIZONTAL else Orientation.VERTICAL,
            )
            if (fleet.canPlace(p)) {
                fleet = fleet.with(p)
                return
            }
        }
    }
}

@Composable
fun PlacementScreen(s: MatchState, session: MatchSession, sound: SoundFx) {
    val deployment = remember(s.round) { Deployment() }
    val haptics = LocalHapticFeedback.current
    val locked = s.myReady
    val fleet = s.fleet ?: deployment.fleet
    var overlayRoot by remember { mutableStateOf(Offset.Zero) }

    fun placed() {
        sound.play(Sfx.CLICK, 0.6f)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight
            val boardSize: Dp = if (wide) min(maxHeight, maxWidth * 0.58f) else min(maxWidth, maxHeight * 0.55f)
            val board = @Composable {
                BoardView(
                    ships = fleet.ships.values.map { ShipOnBoard(it, ShipLook.NORMAL) },
                    shots = emptyMap(),
                    modifier = Modifier
                        .size(boardSize)
                        .pointerInput(locked) {
                            if (locked) return@pointerInput
                            detectDragGestures(
                                onDragStart = { deployment.pickUpAt(it) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    deployment.moveBy(amount)
                                },
                                onDragEnd = { if (deployment.drop()) placed() },
                                onDragCancel = { deployment.cancel() },
                            )
                        },
                    highlight = !locked && deployment.drag != null,
                    onGeometry = { deployment.board = it },
                    onCellTap = if (locked) null else { c -> if (deployment.rotateAt(c)) placed() },
                )
            }
            val panel = @Composable { modifier: Modifier ->
                DeployPanel(s, deployment, locked, modifier, onReady = {
                    if (session.commitFleet(deployment.fleet)) sound.play(Sfx.CLICK)
                }, onPlaced = ::placed)
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    board()
                    panel(Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    board()
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        // The ship under the finger, drawn above everything else.
        Canvas(Modifier.matchParentSize().onGloballyPositioned { overlayRoot = it.positionInRoot() }) {
            val d = deployment.drag ?: return@Canvas
            val g = deployment.board ?: return@Canvas
            val t = deployment.target(d)
            if (t != null && deployment.fleet.canPlace(t)) {
                drawShip(t.type, g.cellTopLeftRoot(t.origin) - overlayRoot, g.cell, t.orientation, ShipLook.GHOST_OK)
            } else {
                val half = g.cell / 2f
                val grabAt = if (d.orientation == Orientation.HORIZONTAL) Offset(d.grab * g.cell + half, half) else Offset(half, d.grab * g.cell + half)
                drawShip(d.type, d.pointer - overlayRoot - grabAt, g.cell, d.orientation, if (t != null) ShipLook.GHOST_BAD else ShipLook.NORMAL)
            }
        }
    }
}

@Composable
private fun DeployPanel(
    s: MatchState,
    deployment: Deployment,
    locked: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onPlaced: () -> Unit,
) {
    Panel(modifier.verticalScroll(rememberScrollState())) {
        Text("Deploy your fleet", color = Sea.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).background(if (s.opponentReady) Sea.Sonar else Sea.Amber, CircleShape))
            Text(
                if (s.opponentReady) "${s.opponentName} is ready and waiting" else "${s.opponentName} is deploying…",
                color = Sea.TextDim,
                fontSize = 16.sp,
            )
        }
        if (locked) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp, color = Sea.Sonar)
                Text("Fleet locked in. Waiting for ${s.opponentName}…", color = Sea.Text, fontSize = 18.sp)
            }
            Hint("Your layout stays on this tablet. Only a sealed fingerprint of it was sent, and it's checked when the game ends.")
            return@Panel
        }

        Hint("Drag ships onto your waters. Tap a ship on the grid to turn it. Drag it off the grid to take it back.")
        val drag = deployment.drag
        val inTray = ShipType.entries.filter { it !in deployment.fleet.ships && !(drag?.from != null && drag.type == it) }
        if (inTray.isEmpty()) {
            Text("All ships deployed.", color = Sea.Sonar, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        for (type in inTray) {
            key(type) {
                TrayShip(type, deployment, dimmed = drag?.type == type && drag.from == null, onPlaced = onPlaced)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton("Shuffle", {
                deployment.fleet = Fleet.random()
                onPlaced()
            })
            QuietButton("Clear", { deployment.fleet = Fleet() }, enabled = deployment.fleet.ships.isNotEmpty())
        }
        BigButton("Ready for battle", onReady, Modifier.fillMaxWidth(), enabled = deployment.fleet.isComplete, color = Sea.Sonar)
    }
}

@Composable
private fun TrayShip(type: ShipType, deployment: Deployment, dimmed: Boolean, onPlaced: () -> Unit) {
    var itemRoot by remember { mutableStateOf(Offset.Zero) }
    val cell = 34.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(
            Modifier
                .width(cell * type.length)
                .height(cell)
                .alpha(if (dimmed) 0.3f else 1f)
                .onGloballyPositioned { itemRoot = it.positionInRoot() }
                .pointerInput(type) {
                    detectTapGestures {
                        deployment.placeAnywhere(type)
                        onPlaced()
                    }
                }
                .pointerInput(type) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val grab = floor(pos.x / size.height).toInt().coerceIn(0, type.length - 1)
                            deployment.startFromTray(type, grab, itemRoot + pos)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            deployment.moveBy(amount)
                        },
                        onDragEnd = { if (deployment.drop()) onPlaced() },
                        onDragCancel = { deployment.cancel() },
                    )
                },
        ) {
            drawShip(type, Offset.Zero, size.height, Orientation.HORIZONTAL, ShipLook.NORMAL)
        }
        Column {
            Text(type.title, color = Sea.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("${type.length} squares", color = Sea.TextDim, fontSize = 13.sp)
        }
    }
}
