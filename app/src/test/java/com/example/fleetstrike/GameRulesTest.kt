package com.example.fleetstrike

import com.example.fleetstrike.game.Coord
import com.example.fleetstrike.game.Difficulty
import com.example.fleetstrike.game.FairPlay
import com.example.fleetstrike.game.Fleet
import com.example.fleetstrike.game.Gunner
import com.example.fleetstrike.game.Orientation
import com.example.fleetstrike.game.Placement
import com.example.fleetstrike.game.ShipType
import com.example.fleetstrike.net.Msg
import com.example.fleetstrike.net.Wifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameRulesTest {

    @Test
    fun randomFleetsAreCompleteAndSurviveEncoding() {
        val random = Random(1)
        repeat(200) {
            val fleet = Fleet.random(random)
            assertTrue(fleet.isComplete)
            assertEquals(17, fleet.cells.size)
            assertEquals(fleet, Fleet.decode(fleet.encode()))
        }
    }

    @Test
    fun overlappingOrOffGridShipsAreRejected() {
        val fleet = Fleet().with(Placement(ShipType.CARRIER, Coord(0, 0), Orientation.HORIZONTAL))
        assertFalse(fleet.canPlace(Placement(ShipType.DESTROYER, Coord(0, 4), Orientation.VERTICAL)))
        assertTrue(fleet.canPlace(Placement(ShipType.DESTROYER, Coord(0, 5), Orientation.VERTICAL)))
        assertFalse(fleet.canPlace(Placement(ShipType.DESTROYER, Coord(9, 9), Orientation.HORIZONTAL)))
        assertNull(Fleet.decode("CARRIER@0,0,H;BATTLESHIP@0,2,V"))
    }

    @Test
    fun rotatingNearTheEdgeNudgesTheShipBack() {
        val fleet = Fleet().with(Placement(ShipType.CARRIER, Coord(8, 0), Orientation.HORIZONTAL))
        val turned = fleet.rotated(ShipType.CARRIER)
        assertEquals(Placement(ShipType.CARRIER, Coord(5, 0), Orientation.VERTICAL), turned)
    }

    @Test
    fun shotsReportHitsSinkingsAndDefeat() {
        val destroyer = Placement(ShipType.DESTROYER, Coord(2, 2), Orientation.HORIZONTAL)
        val fleet = Fleet().with(destroyer)
        assertFalse(fleet.resolve(Coord(0, 0), emptySet()).hit)
        val first = fleet.resolve(Coord(2, 2), emptySet())
        assertTrue(first.hit)
        assertNull(first.sunk)
        val second = fleet.resolve(Coord(2, 3), setOf(Coord(2, 2)))
        assertEquals(destroyer, second.sunk)
        assertTrue(second.defeated)
    }

    @Test
    fun fairPlayCatchesAMovedFleetAndALie() {
        val fleet = Fleet.random(Random(3))
        val salt = FairPlay.newSalt()
        val commitment = FairPlay.commitment(fleet, salt)
        val hitSquare = fleet.ships.getValue(ShipType.CARRIER).origin
        val water = Coord.ALL.first { fleet.at(it) == null }

        assertNull(FairPlay.check(fleet, salt, commitment, mapOf(hitSquare to true, water to false), emptyList()))
        assertNotNull(FairPlay.check(Fleet.random(Random(4)), salt, commitment, emptyMap(), emptyList()))
        assertNotNull(FairPlay.check(fleet, salt, commitment, mapOf(hitSquare to false), emptyList()))
    }

    @Test
    fun everyDifficultyFinishesWithoutRepeatingAShot() {
        for (difficulty in Difficulty.entries) {
            val random = Random(difficulty.ordinal)
            repeat(20) {
                val fleet = Fleet.random(random)
                val shotsTaken = playOut(Gunner(difficulty, random), fleet)
                assertTrue("$difficulty took $shotsTaken shots", shotsTaken <= 100)
            }
        }
    }

    @Test
    fun hardComputerIsMuchBetterThanRandom() {
        val random = Random(42)
        val games = 60
        val hard = (1..games).sumOf { playOut(Gunner(Difficulty.HARD, random), Fleet.random(random)) } / games.toDouble()
        val easy = (1..games).sumOf { playOut(Gunner(Difficulty.EASY, random), Fleet.random(random)) } / games.toDouble()
        assertTrue("hard averaged $hard shots", hard < 62)
        assertTrue("hard ($hard) should beat easy ($easy)", hard < easy)
    }

    @Test
    fun messagesRoundTripAndJunkIsIgnored() {
        val messages = listOf(
            Msg.Hello("fleetstrike", 1, "Ann | Bo", bonusShot = true),
            Msg.Ready("abc"),
            Msg.Start(hostFirst = false),
            Msg.Fire(3, 4),
            Msg.Result(3, 4, hit = true, sunk = "DESTROYER@3,4,H", defeated = false),
            Msg.Reveal("CARRIER@0,0,H", "salt"),
            Msg.Rematch,
            Msg.Bye,
        )
        for (m in messages) assertEquals(m, Msg.decode(Msg.encode(m)))
        assertNull(Msg.decode("not json"))
        assertNull(Msg.decode("""{"t":"teleport"}"""))
    }

    @Test
    fun beaconsParseAndRejectOtherApps() {
        val game = Wifi.parseBeacon(Wifi.beaconText(47811, "Ann|Bo"), "192.168.1.9")
        assertEquals("Ann|Bo", game?.name)
        assertEquals(47811, game?.port)
        assertNull(Wifi.parseBeacon("chessbuddies|1|47791|x", "192.168.1.9"))
    }

    /** Fires until the whole fleet is sunk, reporting sinkings the way the other tablet would. */
    private fun playOut(gunner: Gunner, fleet: Fleet): Int {
        val shots = LinkedHashMap<Coord, Boolean>()
        val sunk = ArrayList<Placement>()
        while (true) {
            val at = gunner.pick(shots, sunk)
            check(at !in shots) { "fired at $at twice" }
            val report = fleet.resolve(at, shots.filterValues { it }.keys)
            shots[at] = report.hit
            report.sunk?.let(sunk::add)
            if (report.defeated) return shots.size
        }
    }
}
