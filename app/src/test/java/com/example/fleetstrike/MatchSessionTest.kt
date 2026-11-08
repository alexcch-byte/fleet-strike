package com.example.fleetstrike

import com.example.fleetstrike.game.Coord
import com.example.fleetstrike.game.Difficulty
import com.example.fleetstrike.game.FairPlay
import com.example.fleetstrike.game.Fleet
import com.example.fleetstrike.net.Msg
import com.example.fleetstrike.net.PipeLink
import com.example.fleetstrike.session.BotPlayer
import com.example.fleetstrike.session.MatchSession
import com.example.fleetstrike.session.Phase
import com.example.fleetstrike.session.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MatchSessionTest {

    /**
     * Sessions run forever (they wait on the link), so they can't be children of the test, and backgroundScope work is
     * skipped by advanceUntilIdle. A detached scope on the test dispatcher gets both right.
     */
    private fun TestScope.games() = CoroutineScope(coroutineContext + Job())

    private fun TestScope.botMatch(bonusShot: Boolean, seed: Int): Pair<MatchSession, MatchSession> {
        val (a, b) = PipeLink.pair()
        val scope = games()
        val host = MatchSession(a, isHost = true, myName = "Host", bonusShot = bonusShot, scope = scope, random = Random(seed))
        val guest = MatchSession(b, isHost = false, myName = "Guest", bonusShot = false, scope = scope, random = Random(seed + 1))
        BotPlayer(host, Difficulty.HARD, scope, Random(seed + 2))
        BotPlayer(guest, Difficulty.EASY, scope, Random(seed + 3))
        return host to guest
    }

    @Test
    fun twoComputersPlayAFullFairGame() = runTest {
        for (bonus in listOf(false, true)) {
            val (host, guest) = botMatch(bonus, seed = if (bonus) 10 else 20)
            advanceUntilIdle()

            val h = host.state.value
            val g = guest.state.value
            assertEquals(Phase.GAME_OVER, h.phase)
            assertEquals(Phase.GAME_OVER, g.phase)
            assertEquals("Guest", h.opponentName)
            assertEquals(bonus, g.bonusShot) // the guest follows the host's house rule
            assertTrue((h.won == true) xor (g.won == true))
            assertEquals(Verdict.FAIR, h.verdict)
            assertEquals(Verdict.FAIR, g.verdict)
            // Each side's view of the other's shots matches exactly.
            assertEquals(h.myShots, g.shotsAtMe)
            assertEquals(g.myShots, h.shotsAtMe)
            assertEquals(g.fleet, h.enemyFleet)
            assertEquals(h.fleet, g.enemyFleet)
            assertNull(h.disconnected)
        }
    }

    @Test
    fun rematchStartsAFreshRoundAndKeepsScore() = runTest {
        val (host, guest) = botMatch(bonusShot = false, seed = 5)
        advanceUntilIdle()

        host.requestRematch() // the guest bot accepts, then both bots play the second game
        advanceUntilIdle()

        val h = host.state.value
        assertEquals(2, h.round)
        assertEquals(Phase.GAME_OVER, h.phase)
        assertEquals(2, h.wins + h.losses)
        assertEquals(h.wins, guest.state.value.losses)
        assertEquals(Verdict.FAIR, h.verdict)
    }

    @Test
    fun aPlayerWhoLiesAboutAHitIsCaught() = runTest {
        val (a, b) = PipeLink.pair()
        val host = MatchSession(a, isHost = true, myName = "Host", bonusShot = false, scope = games(), random = Random(1))
        runCurrent()

        // A hand-written "cheater" on the far end of the link.
        val cheaterFleet = Fleet.random(Random(9))
        val salt = FairPlay.newSalt()
        fun say(m: Msg) = b.send(Msg.encode(m))
        say(Msg.Hello("fleetstrike", 1, "Cheater", bonusShot = false))
        say(Msg.Ready(FairPlay.commitment(cheaterFleet, salt)))
        runCurrent()
        assertTrue(host.commitFleet(Fleet.random(Random(2))))
        runCurrent()

        // Make sure it's the host's turn, then claim the host's first shot was a miss even though it hit.
        val started = Msg.decode(generateSequence { b.incoming.tryReceive().getOrNull() }.last()) as Msg.Start
        if (!started.hostFirst) {
            say(Msg.Fire(0, 0))
            runCurrent()
        }
        val target: Coord = cheaterFleet.ships.values.first().origin
        assertTrue(host.fire(target))
        runCurrent()
        say(Msg.Result(target.row, target.col, hit = false, defeated = true))
        say(Msg.Reveal(cheaterFleet.encode(), salt))
        runCurrent()

        val s = host.state.value
        assertEquals(Phase.GAME_OVER, s.phase)
        assertEquals(Verdict.SUSPICIOUS, s.verdict)
        assertTrue(s.verdictNote!!.contains(target.label))
    }

    @Test
    fun outOfTurnShotsAndBadVersionsAreRefused() = runTest {
        val (a, b) = PipeLink.pair()
        val host = MatchSession(a, isHost = true, myName = "Host", bonusShot = false, scope = games())
        b.send(Msg.encode(Msg.Hello("fleetstrike", 99, "Future", bonusShot = false)))
        runCurrent()
        assertNotNull(host.state.value.disconnected)
        assertFalse(host.fire(Coord(0, 0)))
    }

    @Test
    fun leavingTellsTheOtherPlayer() = runTest {
        val (host, guest) = botMatch(bonusShot = false, seed = 7)
        runCurrent()
        host.leave()
        advanceUntilIdle()
        assertEquals("Host left the game.", guest.state.value.disconnected)
    }
}
