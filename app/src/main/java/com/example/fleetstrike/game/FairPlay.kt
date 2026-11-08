package com.example.fleetstrike.game

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Keeps both players honest without either tablet seeing the other's fleet during the battle.
 *
 * When a player locks in their fleet they send only a fingerprint of it (a SHA-256 hash of the layout plus a secret salt).
 * When the game ends each side reveals its layout and salt; the other tablet re-computes the fingerprint and replays every
 * hit/miss/sunk report against the revealed fleet. Moving a ship mid-game or answering "miss" on a hit is caught.
 */
object FairPlay {

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    fun commitment(fleet: Fleet, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("${fleet.encode()}#$salt".toByteArray(Charsets.UTF_8)).toHex()

    /**
     * Checks the opponent's revealed fleet against what they told us during the game.
     * Returns null when everything adds up, otherwise a sentence saying what didn't.
     */
    fun check(
        revealed: Fleet?,
        salt: String,
        commitment: String?,
        myShots: Map<Coord, Boolean>,
        reportedSunk: List<Placement>,
    ): String? {
        if (revealed == null || !revealed.isComplete) return "Their revealed fleet isn't a legal fleet."
        if (commitment == null || commitment(revealed, salt) != commitment) return "Their fleet doesn't match the one they locked in before the battle."
        for ((c, hit) in myShots) {
            val really = revealed.at(c) != null
            if (really != hit) return "They called ${c.label} a ${if (hit) "hit" else "miss"}, but it was a ${if (really) "hit" else "miss"}."
        }
        for (p in reportedSunk) {
            if (revealed.ships[p.type] != p) return "They misreported where their ${p.type.title} was."
        }
        val hits = myShots.filterValues { it }.keys
        for (p in revealed.ships.values) {
            if (hits.containsAll(p.cells) && reportedSunk.none { it.type == p.type }) return "They never admitted their ${p.type.title} was sunk."
        }
        return null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
