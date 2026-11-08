package com.example.fleetstrike.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.example.fleetstrike.game.Coord
import com.example.fleetstrike.game.GRID
import com.example.fleetstrike.game.Placement
import com.example.fleetstrike.ui.theme.Sea
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Where a board's squares are. "Local" positions are inside the board; "root" ones are in the whole window. */
data class BoardGeometry(val boardInRoot: Offset, val margin: Float, val cell: Float) {
    val gridLocal: Offset get() = Offset(margin, margin)

    fun cellAtLocal(p: Offset): Coord? {
        val c = Coord(floor((p.y - margin) / cell).toInt(), floor((p.x - margin) / cell).toInt())
        return c.takeIf { it.inBounds }
    }

    fun cellAtRoot(p: Offset): Coord? = cellAtLocal(p - boardInRoot)

    fun cellTopLeftLocal(c: Coord) = Offset(margin + c.col * cell, margin + c.row * cell)

    fun cellTopLeftRoot(c: Coord) = boardInRoot + cellTopLeftLocal(c)

    companion object {
        /** The board is square: a strip for the row/column labels, then the grid, then a little breathing room. */
        fun forWidth(width: Float, boardInRoot: Offset = Offset.Zero): BoardGeometry {
            val cell = width / (GRID + 0.75f)
            return BoardGeometry(boardInRoot, margin = cell * 0.6f, cell = cell)
        }
    }
}

data class ShipOnBoard(val placement: Placement, val look: ShipLook)

/**
 * One player's ocean: grid, labels, ships, and the pegs where shots have landed.
 *
 * [newestShot] gets a short animation (an incoming shell, then a burst). [aiming] marks a shot still in the air.
 * [onCellTap] makes squares tappable; [onGeometry] reports where the squares are for drag-and-drop.
 */
@Composable
fun BoardView(
    ships: List<ShipOnBoard>,
    shots: Map<Coord, Boolean>,
    modifier: Modifier = Modifier,
    newestShot: Coord? = null,
    aiming: Coord? = null,
    highlight: Boolean = false,
    dimmed: Boolean = false,
    onGeometry: ((BoardGeometry) -> Unit)? = null,
    onCellTap: ((Coord) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val transition = rememberInfiniteTransition(label = "sea")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "waves")
    val flicker by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(380, easing = LinearEasing), RepeatMode.Reverse), label = "flicker")

    val landing = remember { Animatable(1f) }
    LaunchedEffect(newestShot) {
        if (newestShot != null) {
            landing.snapTo(0f)
            landing.animateTo(1f, tween(750, easing = LinearEasing))
        }
    }

    val tap by rememberUpdatedState(onCellTap)
    val geometryCallback by rememberUpdatedState(onGeometry)

    Box(
        modifier
            .aspectRatio(1f)
            .onGloballyPositioned { coords ->
                geometryCallback?.invoke(BoardGeometry.forWidth(coords.size.width.toFloat(), coords.positionInRoot()))
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val handler = tap ?: return@detectTapGestures
                    BoardGeometry.forWidth(size.width.toFloat()).cellAtLocal(pos)?.let(handler)
                }
            }
            .drawWithCache {
                val g = BoardGeometry.forWidth(size.width)
                val cell = g.cell
                val gridSize = cell * GRID
                val labelStyle = TextStyle(
                    color = Sea.Label,
                    fontSize = (cell * 0.34f).toSp(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                val rowLabels = (0 until GRID).map { measurer.measure(('A' + it).toString(), labelStyle) }
                val colLabels = (0 until GRID).map { measurer.measure((it + 1).toString(), labelStyle) }
                val wave = Path().apply {
                    moveTo(cell * 0.18f, cell * 0.66f)
                    quadraticTo(cell * 0.34f, cell * 0.56f, cell * 0.5f, cell * 0.66f)
                    quadraticTo(cell * 0.66f, cell * 0.76f, cell * 0.82f, cell * 0.66f)
                }
                val oceanBrush = Brush.verticalGradient(listOf(Sea.OceanTop, Sea.OceanBottom), startY = g.margin, endY = g.margin + gridSize)

                onDrawBehind {
                    drawRoundRect(Sea.Deep, cornerRadius = CornerRadius(cell * 0.3f))
                    drawRect(oceanBrush, topLeft = g.gridLocal, size = Size(gridSize, gridSize))

                    // Gently moving swell, so the sea never looks frozen.
                    for (c in Coord.ALL) {
                        val t = (phase + c.row * 0.11f + c.col * 0.067f) % 1f
                        val a = 0.03f + 0.07f * ((sin(2 * PI * t).toFloat() + 1f) / 2f)
                        translate(g.margin + c.col * cell, g.margin + c.row * cell) {
                            drawPath(wave, Color.White.copy(alpha = a), style = Stroke(cell * 0.035f, cap = StrokeCap.Round))
                        }
                    }

                    for (i in 0..GRID) {
                        val o = g.margin + i * cell
                        drawLine(Sea.GridLine.copy(alpha = 0.45f), Offset(g.margin, o), Offset(g.margin + gridSize, o), strokeWidth = 1.2f)
                        drawLine(Sea.GridLine.copy(alpha = 0.45f), Offset(o, g.margin), Offset(o, g.margin + gridSize), strokeWidth = 1.2f)
                    }
                    for (i in 0 until GRID) {
                        val row = rowLabels[i]
                        drawText(row, topLeft = Offset((g.margin - row.size.width) / 2f, g.margin + i * cell + (cell - row.size.height) / 2f))
                        val col = colLabels[i]
                        drawText(col, topLeft = Offset(g.margin + i * cell + (cell - col.size.width) / 2f, (g.margin - col.size.height) / 2f))
                    }

                    for (ship in ships) {
                        drawShip(ship.placement.type, g.cellTopLeftLocal(ship.placement.origin), cell, ship.placement.orientation, ship.look)
                    }

                    val land = landing.value
                    for ((c, hit) in shots) {
                        val center = g.cellTopLeftLocal(c) + Offset(cell / 2f, cell / 2f)
                        if (c == newestShot && land < 1f) drawLanding(center, cell, hit, land, flicker)
                        else if (hit) drawHit(center, cell, flicker, 1f) else drawMiss(center, cell, 1f)
                    }

                    if (aiming != null) drawCrosshair(g.cellTopLeftLocal(aiming) + Offset(cell / 2f, cell / 2f), cell, flicker)

                    if (dimmed) drawRect(Color(0xFF050D18).copy(alpha = 0.35f), topLeft = g.gridLocal, size = Size(gridSize, gridSize))
                    if (highlight) {
                        drawRect(
                            Sea.Amber.copy(alpha = 0.45f + 0.4f * flicker),
                            topLeft = g.gridLocal,
                            size = Size(gridSize, gridSize),
                            style = Stroke(cell * 0.06f),
                        )
                    }
                }
            },
    )
}

fun DrawScope.drawHit(center: Offset, cell: Float, flicker: Float, alpha: Float) {
    val r = cell * (0.42f + 0.06f * flicker)
    drawCircle(
        Brush.radialGradient(
            listOf(Sea.Flame.copy(alpha = 0.95f), Color(0xFFFF8A3D).copy(alpha = 0.8f), Sea.Hit.copy(alpha = 0.35f), Color.Transparent),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
        alpha = alpha,
    )
    drawCircle(Sea.Hit, radius = cell * 0.14f, center = center, alpha = alpha)
    drawCircle(Color(0xFF5A1208), radius = cell * 0.14f, center = center, style = Stroke(cell * 0.03f), alpha = alpha)
}

fun DrawScope.drawMiss(center: Offset, cell: Float, alpha: Float) {
    drawCircle(Sea.Miss.copy(alpha = 0.22f), radius = cell * 0.34f, center = center, style = Stroke(cell * 0.035f), alpha = alpha)
    drawCircle(Sea.Miss.copy(alpha = 0.45f), radius = cell * 0.22f, center = center, style = Stroke(cell * 0.035f), alpha = alpha)
    drawCircle(Sea.Miss, radius = cell * 0.1f, center = center, alpha = alpha)
}

/** A shell dropping onto the square (its shadow closing in), then a splash or a burst of fire. */
private fun DrawScope.drawLanding(center: Offset, cell: Float, hit: Boolean, t: Float, flicker: Float) {
    val drop = 0.4f
    if (t < drop) {
        val k = t / drop
        drawCircle(Color.Black.copy(alpha = 0.15f + 0.35f * k), radius = cell * (0.55f - 0.4f * k), center = center)
        drawCircle(Sea.Amber, radius = cell * 0.07f, center = center, alpha = 0.4f + 0.6f * k)
        return
    }
    val k = (t - drop) / (1f - drop)
    val ring = if (hit) Sea.Flame else Sea.Miss
    drawCircle(ring.copy(alpha = (1f - k) * 0.9f), radius = cell * (0.2f + 0.9f * k), center = center, style = Stroke(cell * 0.07f * (1f - k) + 1f))
    if (hit) {
        drawCircle(Color.White.copy(alpha = (1f - k) * 0.8f), radius = cell * 0.45f * (1f - k * 0.5f), center = center)
        drawHit(center, cell, flicker, k.coerceAtLeast(0.3f))
    } else {
        drawMiss(center, cell, k.coerceAtLeast(0.3f))
    }
}

private fun DrawScope.drawCrosshair(center: Offset, cell: Float, pulse: Float) {
    val r = cell * (0.32f + 0.08f * pulse)
    val color = Sea.Amber
    drawCircle(color, radius = r, center = center, style = Stroke(cell * 0.06f))
    val arm = cell * 0.5f
    drawLine(color, center - Offset(arm, 0f), center - Offset(r * 0.5f, 0f), strokeWidth = cell * 0.05f)
    drawLine(color, center + Offset(r * 0.5f, 0f), center + Offset(arm, 0f), strokeWidth = cell * 0.05f)
    drawLine(color, center - Offset(0f, arm), center - Offset(0f, r * 0.5f), strokeWidth = cell * 0.05f)
    drawLine(color, center + Offset(0f, r * 0.5f), center + Offset(0f, arm), strokeWidth = cell * 0.05f)
}
