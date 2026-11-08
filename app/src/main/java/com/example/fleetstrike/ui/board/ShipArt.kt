package com.example.fleetstrike.ui.board

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.example.fleetstrike.game.Orientation
import com.example.fleetstrike.game.ShipType

enum class ShipLook { NORMAL, SUNK, REVEALED, GHOST_OK, GHOST_BAD }

private class Paint(val hullTop: Color, val hullBottom: Color, val deck: Color, val detail: Color, val outline: Color, val alpha: Float)

private fun paintFor(look: ShipLook, type: ShipType): Paint {
    val sub = type == ShipType.SUBMARINE
    return when (look) {
        ShipLook.NORMAL, ShipLook.GHOST_OK, ShipLook.GHOST_BAD -> Paint(
            hullTop = if (sub) Color(0xFF6E7C8A) else Color(0xFFA4B1BE),
            hullBottom = if (sub) Color(0xFF465260) else Color(0xFF6D7B89),
            deck = if (sub) Color(0xFF55626F) else Color(0xFF7F8D9B),
            detail = Color(0xFFCAD4DE),
            outline = Color(0xFF1C2833),
            alpha = if (look == ShipLook.NORMAL) 1f else 0.75f,
        )
        ShipLook.SUNK -> Paint(
            hullTop = Color(0xFF5E4A47),
            hullBottom = Color(0xFF3A2C2B),
            deck = Color(0xFF4A3B39),
            detail = Color(0xFF7A6461),
            outline = Color(0xFF140C0B),
            alpha = 0.95f,
        )
        ShipLook.REVEALED -> Paint(
            hullTop = Color(0xFFB9C4CF),
            hullBottom = Color(0xFF8592A0),
            deck = Color(0xFF94A2B0),
            detail = Color(0xFFE2E8EE),
            outline = Color(0xFF1C2833),
            alpha = 0.6f,
        )
    }
}

/**
 * Draws a ship occupying [type].length cells starting at [topLeft]. Ships are drawn bow-right and turned a quarter turn
 * clockwise (bow-down) when vertical.
 */
fun DrawScope.drawShip(type: ShipType, topLeft: Offset, cell: Float, orientation: Orientation, look: ShipLook) {
    if (orientation == Orientation.HORIZONTAL) {
        translate(topLeft.x, topLeft.y) { drawShipHorizontal(type, cell, look) }
    } else {
        translate(topLeft.x + cell, topLeft.y) {
            rotate(90f, pivot = Offset.Zero) { drawShipHorizontal(type, cell, look) }
        }
    }
}

private fun DrawScope.drawShipHorizontal(type: ShipType, cell: Float, look: ShipLook) {
    val p = paintFor(look, type)
    val w = type.length * cell
    val cy = cell / 2f
    val stroke = (cell * 0.045f).coerceAtLeast(1f)

    val beam = when (type) {
        ShipType.CARRIER -> cell * 0.74f
        ShipType.BATTLESHIP -> cell * 0.62f
        ShipType.CRUISER -> cell * 0.56f
        ShipType.SUBMARINE -> cell * 0.46f
        ShipType.DESTROYER -> cell * 0.5f
    }
    val stern = cell * 0.1f
    val bow = w - cell * 0.06f
    val bowLength = when (type) {
        ShipType.CARRIER -> cell * 0.55f
        ShipType.SUBMARINE -> cell * 0.7f
        else -> cell * 1.05f
    }
    val half = beam / 2f
    val hull = Path().apply {
        if (type == ShipType.SUBMARINE) {
            // A cigar: round at the back, gently pointed at the front.
            moveTo(stern + half, cy - half)
            lineTo(bow - bowLength, cy - half)
            cubicTo(bow - bowLength * 0.3f, cy - half, bow, cy - half * 0.35f, bow, cy)
            cubicTo(bow, cy + half * 0.35f, bow - bowLength * 0.3f, cy + half, bow - bowLength, cy + half)
            lineTo(stern + half, cy + half)
            cubicTo(stern, cy + half, stern, cy - half, stern + half, cy - half)
        } else {
            val r = half * 0.55f
            moveTo(stern + r, cy - half)
            lineTo(bow - bowLength, cy - half)
            quadraticTo(bow - bowLength * 0.25f, cy - half, bow, cy)
            quadraticTo(bow - bowLength * 0.25f, cy + half, bow - bowLength, cy + half)
            lineTo(stern + r, cy + half)
            quadraticTo(stern, cy + half, stern, cy + half - r)
            lineTo(stern, cy - half + r)
            quadraticTo(stern, cy - half, stern + r, cy - half)
        }
        close()
    }

    // Shadow in the water, then the hull with light from above.
    translate(cell * 0.04f, cell * 0.06f) { drawPath(hull, Color.Black.copy(alpha = 0.28f * p.alpha)) }
    drawPath(hull, Brush.verticalGradient(listOf(p.hullTop, p.hullBottom), startY = cy - half, endY = cy + half), alpha = p.alpha)

    val detailAlpha = p.alpha
    fun deckBlock(from: Float, to: Float, height: Float, color: Color = p.detail) {
        drawRoundRect(
            color = color,
            topLeft = Offset(from, cy - height / 2f),
            size = Size(to - from, height),
            cornerRadius = CornerRadius(height * 0.25f),
            alpha = detailAlpha,
        )
        drawRoundRect(
            color = p.outline,
            topLeft = Offset(from, cy - height / 2f),
            size = Size(to - from, height),
            cornerRadius = CornerRadius(height * 0.25f),
            style = Stroke(stroke * 0.8f),
            alpha = detailAlpha,
        )
    }

    fun turret(x: Float, facingBow: Boolean) {
        val r = beam * 0.2f
        val dir = if (facingBow) 1f else -1f
        drawLine(p.outline, Offset(x, cy), Offset(x + dir * r * 2.3f, cy), strokeWidth = r * 0.5f, cap = StrokeCap.Round, alpha = detailAlpha)
        drawCircle(p.detail, radius = r, center = Offset(x, cy), alpha = detailAlpha)
        drawCircle(p.outline, radius = r, center = Offset(x, cy), style = Stroke(stroke * 0.8f), alpha = detailAlpha)
    }

    fun funnel(x: Float) {
        drawOval(p.outline, topLeft = Offset(x - beam * 0.12f, cy - beam * 0.16f), size = Size(beam * 0.24f, beam * 0.32f), alpha = detailAlpha)
    }

    when (type) {
        ShipType.CARRIER -> {
            // Flight deck with a dashed centre line, an island on the starboard side and a few parked aircraft.
            val deckTop = cy - half * 0.78f
            drawRoundRect(
                color = Color(0xFF4A535D),
                topLeft = Offset(stern + cell * 0.12f, deckTop),
                size = Size(bow - bowLength * 0.6f - stern - cell * 0.12f, half * 1.56f),
                cornerRadius = CornerRadius(cell * 0.06f),
                alpha = detailAlpha,
            )
            drawLine(
                Color(0xFFE8EEF4), Offset(stern + cell * 0.3f, cy), Offset(bow - bowLength * 0.8f, cy),
                strokeWidth = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(cell * 0.18f, cell * 0.12f)), alpha = 0.8f * detailAlpha,
            )
            drawLine(
                Color(0xFFE8EEF4).copy(alpha = 0.5f), Offset(stern + cell * 0.4f, cy + half * 0.5f), Offset(w * 0.45f, cy - half * 0.15f),
                strokeWidth = stroke * 0.8f, alpha = detailAlpha,
            )
            drawRoundRect(p.detail, topLeft = Offset(w * 0.58f, cy - half * 0.98f), size = Size(cell * 0.62f, half * 0.48f), cornerRadius = CornerRadius(cell * 0.05f), alpha = detailAlpha)
            drawRoundRect(p.outline, topLeft = Offset(w * 0.58f, cy - half * 0.98f), size = Size(cell * 0.62f, half * 0.48f), cornerRadius = CornerRadius(cell * 0.05f), style = Stroke(stroke * 0.8f), alpha = detailAlpha)
            for (i in 0 until 3) {
                val x = w * 0.2f + i * cell * 0.55f
                val y = cy + half * 0.38f
                val s = cell * 0.13f
                val plane = Path().apply {
                    moveTo(x + s * 1.4f, y)
                    lineTo(x - s, y - s)
                    lineTo(x - s * 0.5f, y)
                    lineTo(x - s, y + s)
                    close()
                }
                drawPath(plane, p.detail, alpha = 0.85f * detailAlpha)
            }
        }
        ShipType.BATTLESHIP -> {
            deckBlock(w * 0.3f, w * 0.56f, beam * 0.56f)
            deckBlock(w * 0.36f, w * 0.47f, beam * 0.32f, p.deck)
            funnel(w * 0.52f)
            turret(w * 0.17f, facingBow = false)
            turret(w * 0.66f, facingBow = true)
            turret(w * 0.79f, facingBow = true)
        }
        ShipType.CRUISER -> {
            deckBlock(w * 0.34f, w * 0.6f, beam * 0.55f)
            funnel(w * 0.53f)
            turret(w * 0.18f, facingBow = false)
            turret(w * 0.74f, facingBow = true)
        }
        ShipType.SUBMARINE -> {
            deckBlock(w * 0.5f, w * 0.68f, beam * 0.5f, p.deck)
            drawLine(p.outline, Offset(stern + cell * 0.05f, cy - half * 1.2f), Offset(stern + cell * 0.05f, cy + half * 1.2f), strokeWidth = stroke * 1.6f, cap = StrokeCap.Round, alpha = detailAlpha)
        }
        ShipType.DESTROYER -> {
            deckBlock(w * 0.3f, w * 0.55f, beam * 0.55f)
            funnel(w * 0.4f)
            turret(w * 0.7f, facingBow = true)
        }
    }

    drawPath(hull, p.outline, style = Stroke(stroke), alpha = p.alpha)

    if (look == ShipLook.GHOST_OK || look == ShipLook.GHOST_BAD) {
        val glow = if (look == ShipLook.GHOST_OK) Color(0xFF3DDC97) else Color(0xFFFF6B6B)
        drawPath(hull, glow, style = Stroke(stroke * 2.5f), alpha = 0.9f)
    }
}
