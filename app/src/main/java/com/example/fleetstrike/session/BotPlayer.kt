package com.example.fleetstrike.session

import com.example.fleetstrike.game.Difficulty
import com.example.fleetstrike.game.Fleet
import com.example.fleetstrike.game.Gunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The practice opponent. It plays a real [MatchSession] on the far end of an in-memory link, so practice games run
 * through exactly the same rules and messages as games between two tablets.
 */
class BotPlayer(
    private val session: MatchSession,
    difficulty: Difficulty,
    scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    private val gunner = Gunner(difficulty, random)

    private val job = scope.launch {
        // collectLatest restarts the pause whenever anything changes, so the bot always acts on the latest state.
        session.state.collectLatest { s ->
            if (s.disconnected != null) return@collectLatest
            when (s.phase) {
                Phase.PLACEMENT -> if (!s.myReady) {
                    delay(1200)
                    session.commitFleet(Fleet.random(random))
                }
                Phase.BATTLE -> if (s.canFire) {
                    delay(650L + random.nextLong(600))
                    session.fire(gunner.pick(s.myShots, s.enemySunk))
                }
                Phase.GAME_OVER -> if (s.opponentWantsRematch && !s.iWantRematch) {
                    delay(700)
                    session.requestRematch()
                }
                Phase.HANDSHAKE -> Unit
            }
        }
    }

    fun stop() {
        job.cancel()
        session.leave()
    }
}
