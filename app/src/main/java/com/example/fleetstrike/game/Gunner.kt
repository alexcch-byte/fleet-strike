package com.example.fleetstrike.game

import kotlin.random.Random

enum class Difficulty(val title: String) {
    EASY("Easy"),
    NORMAL("Normal"),
    HARD("Hard"),
}

/**
 * The computer's targeting officer. It only knows what a human would: where it has fired, which shots hit, and which
 * enemy ships have been reported sunk.
 *
 * - Easy fires at random until it hits something, then pokes at the squares next to the hit.
 * - Hard counts, for every square, how many ways the surviving enemy ships could still be lying across it, and fires at the
 *   likeliest square. Once it has a hit that isn't part of a sunk ship it only counts layouts that pass through that hit.
 * - Normal plays like Hard but takes a random pot-shot about a third of the time while it's hunting.
 */
class Gunner(private val difficulty: Difficulty, private val random: Random = Random.Default) {

    fun pick(shots: Map<Coord, Boolean>, sunk: List<Placement>): Coord {
        val unknown = Coord.ALL.filter { it !in shots }
        check(unknown.isNotEmpty()) { "No squares left to fire at" }
        val sunkCells = sunk.flatMapTo(HashSet()) { it.cells }
        val openHits = shots.filter { (c, hit) -> hit && c !in sunkCells }.keys

        return when (difficulty) {
            Difficulty.EASY -> nextTo(openHits, shots) ?: unknown.random(random)
            Difficulty.NORMAL ->
                if (openHits.isEmpty() && random.nextFloat() < 0.35f) unknown.random(random)
                else likeliest(shots, sunk, sunkCells, openHits, unknown)
            Difficulty.HARD -> likeliest(shots, sunk, sunkCells, openHits, unknown)
        }
    }

    private fun nextTo(openHits: Set<Coord>, shots: Map<Coord, Boolean>): Coord? =
        openHits.flatMap { it.neighbors() }.filter { it !in shots }.randomOrNull(random)

    private fun likeliest(
        shots: Map<Coord, Boolean>,
        sunk: List<Placement>,
        sunkCells: Set<Coord>,
        openHits: Set<Coord>,
        unknown: List<Coord>,
    ): Coord {
        val score = Array(GRID) { IntArray(GRID) }
        val afloat = ShipType.entries.filter { type -> sunk.none { it.type == type } }
        for (type in afloat) {
            for (orientation in Orientation.entries) {
                for (origin in Coord.ALL) {
                    val p = Placement(type, origin, orientation)
                    if (!p.inBounds) continue
                    if (p.cells.any { shots[it] == false || it in sunkCells }) continue
                    val through = p.cells.count { it in openHits }
                    if (openHits.isNotEmpty() && through == 0) continue
                    val weight = if (openHits.isEmpty()) 1 else 1 + 25 * through
                    for (c in p.cells) if (c !in shots) score[c.row][c.col] += weight
                }
            }
        }
        val best = unknown.maxOf { score[it.row][it.col] }
        if (best == 0) return nextTo(openHits, shots) ?: unknown.random(random)
        return unknown.filter { score[it.row][it.col] == best }.random(random)
    }
}
