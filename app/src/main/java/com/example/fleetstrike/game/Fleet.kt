package com.example.fleetstrike.game

import kotlin.random.Random

/** Rows and columns on each player's ocean. */
const val GRID = 10

/** One square of the ocean. Rows are lettered A–J, columns numbered 1–10. */
data class Coord(val row: Int, val col: Int) {
    val inBounds: Boolean get() = row in 0 until GRID && col in 0 until GRID

    /** "C7" style name, as players call out shots. */
    val label: String get() = "${'A' + row}${col + 1}"

    fun neighbors(): List<Coord> =
        listOf(Coord(row - 1, col), Coord(row + 1, col), Coord(row, col - 1), Coord(row, col + 1)).filter { it.inBounds }

    companion object {
        val ALL: List<Coord> = (0 until GRID).flatMap { r -> (0 until GRID).map { c -> Coord(r, c) } }
    }
}

enum class ShipType(val length: Int, val title: String) {
    CARRIER(5, "Carrier"),
    BATTLESHIP(4, "Battleship"),
    CRUISER(3, "Cruiser"),
    SUBMARINE(3, "Submarine"),
    DESTROYER(2, "Destroyer"),
}

enum class Orientation {
    HORIZONTAL, VERTICAL;

    fun flipped() = if (this == HORIZONTAL) VERTICAL else HORIZONTAL
}

/** A ship laid on the grid: [origin] is its stern (left end, or top end when vertical). */
data class Placement(val type: ShipType, val origin: Coord, val orientation: Orientation) {
    val cells: List<Coord> = List(type.length) { i ->
        if (orientation == Orientation.HORIZONTAL) Coord(origin.row, origin.col + i) else Coord(origin.row + i, origin.col)
    }

    val inBounds: Boolean get() = cells.all { it.inBounds }

    fun encode(): String = "${type.name}@${origin.row},${origin.col},${if (orientation == Orientation.HORIZONTAL) 'H' else 'V'}"

    companion object {
        fun decode(text: String): Placement? {
            val type = ShipType.entries.firstOrNull { it.name == text.substringBefore('@') } ?: return null
            val parts = text.substringAfter('@', "").split(',')
            if (parts.size != 3) return null
            val row = parts[0].toIntOrNull() ?: return null
            val col = parts[1].toIntOrNull() ?: return null
            val orientation = when (parts[2]) {
                "H" -> Orientation.HORIZONTAL
                "V" -> Orientation.VERTICAL
                else -> return null
            }
            return Placement(type, Coord(row, col), orientation)
        }
    }
}

/** What happened when a shot landed on a fleet. */
data class ShotReport(val hit: Boolean, val sunk: Placement?, val defeated: Boolean)

/** A player's ships. Ships may touch but never overlap, and each type appears at most once. */
data class Fleet(val ships: Map<ShipType, Placement> = emptyMap()) {

    val isComplete: Boolean get() = ships.size == ShipType.entries.size && isLegal

    /** In bounds, no overlaps, and every entry is filed under its own type. */
    val isLegal: Boolean
        get() {
            val seen = HashSet<Coord>()
            return ships.all { (type, p) -> p.type == type && p.inBounds && p.cells.all(seen::add) }
        }

    val cells: Set<Coord> get() = ships.values.flatMapTo(HashSet()) { it.cells }

    fun at(c: Coord): Placement? = ships.values.firstOrNull { c in it.cells }

    /** True if [p] fits: on the grid and clear of every other ship (its own old spot doesn't count). */
    fun canPlace(p: Placement): Boolean =
        p.inBounds && ships.values.none { other -> other.type != p.type && other.cells.any { it in p.cells } }

    fun with(p: Placement) = Fleet(ships + (p.type to p))

    fun without(type: ShipType) = Fleet(ships - type)

    /** Where this ship can turn to when tapped: the same stern if it fits, otherwise nudged back so it stays on the grid. */
    fun rotated(type: ShipType): Placement? {
        val p = ships[type] ?: return null
        val turned = p.orientation.flipped()
        for (back in 0 until type.length) {
            val origin = if (turned == Orientation.HORIZONTAL) Coord(p.origin.row, p.origin.col - back) else Coord(p.origin.row - back, p.origin.col)
            val candidate = Placement(type, origin, turned)
            if (canPlace(candidate)) return candidate
        }
        return null
    }

    /** Applies a shot to this fleet, given the squares already hit before it. */
    fun resolve(shot: Coord, earlierHits: Set<Coord>): ShotReport {
        val ship = at(shot) ?: return ShotReport(hit = false, sunk = null, defeated = false)
        val hits = earlierHits + shot
        val sunk = ship.takeIf { it.cells.all(hits::contains) }
        return ShotReport(hit = true, sunk = sunk, defeated = cells.all(hits::contains))
    }

    /** A stable text form: one placement per ship in a fixed order. */
    fun encode(): String = ShipType.entries.mapNotNull { ships[it]?.encode() }.joinToString(";")

    companion object {
        fun decode(text: String): Fleet? {
            if (text.isBlank()) return Fleet()
            val placements = text.split(';').map { Placement.decode(it) ?: return null }
            if (placements.map { it.type }.toSet().size != placements.size) return null
            return Fleet(placements.associateBy { it.type }).takeIf { it.isLegal }
        }

        /** A complete fleet laid out at random, keeping whatever is already in [start]. */
        fun random(random: Random = Random.Default, start: Fleet = Fleet()): Fleet {
            var fleet = start
            for (type in ShipType.entries) {
                if (type in fleet.ships) continue
                while (true) {
                    val orientation = if (random.nextBoolean()) Orientation.HORIZONTAL else Orientation.VERTICAL
                    val p = Placement(type, Coord(random.nextInt(GRID), random.nextInt(GRID)), orientation)
                    if (fleet.canPlace(p)) {
                        fleet = fleet.with(p)
                        break
                    }
                }
            }
            return fleet
        }
    }
}
