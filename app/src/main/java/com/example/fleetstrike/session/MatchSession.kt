package com.example.fleetstrike.session

import com.example.fleetstrike.game.Coord
import com.example.fleetstrike.game.FairPlay
import com.example.fleetstrike.game.Fleet
import com.example.fleetstrike.game.Placement
import com.example.fleetstrike.game.ShipType
import com.example.fleetstrike.net.APP_ID
import com.example.fleetstrike.net.Link
import com.example.fleetstrike.net.Msg
import com.example.fleetstrike.net.PROTOCOL_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Phase { HANDSHAKE, PLACEMENT, BATTLE, GAME_OVER }

enum class Verdict { PENDING, FAIR, SUSPICIOUS }

/** One player's view of a match: everything the screen needs and nothing it's not allowed to know. */
data class MatchState(
    val isHost: Boolean,
    val myName: String,
    /** House rule: a hit earns the shooter another shot. Set by the host. */
    val bonusShot: Boolean,
    val phase: Phase = Phase.HANDSHAKE,
    val round: Int = 1,
    val opponentName: String = "",
    val fleet: Fleet? = null,
    val myReady: Boolean = false,
    val opponentReady: Boolean = false,
    val myTurn: Boolean = false,
    /** Our shot that's in the air, waiting for the other tablet to say what it hit. */
    val aiming: Coord? = null,
    val myShots: Map<Coord, Boolean> = emptyMap(),
    val shotsAtMe: Map<Coord, Boolean> = emptyMap(),
    /** The most recent shot each way, so the boards can animate it landing. */
    val lastMyShot: Coord? = null,
    val lastShotAtMe: Coord? = null,
    val enemySunk: List<Placement> = emptyList(),
    val mySunk: Set<ShipType> = emptySet(),
    val won: Boolean? = null,
    /** The opponent's whole fleet, shown once the game is over. */
    val enemyFleet: Fleet? = null,
    val verdict: Verdict = Verdict.PENDING,
    val verdictNote: String? = null,
    val iWantRematch: Boolean = false,
    val opponentWantsRematch: Boolean = false,
    val wins: Int = 0,
    val losses: Int = 0,
    /** Why the match can't go on (the other player left, the connection dropped…), or null while it can. */
    val disconnected: String? = null,
) {
    val canFire: Boolean get() = phase == Phase.BATTLE && myTurn && aiming == null && disconnected == null
}

/** Moments the screen reacts to with sound, vibration or a message. */
sealed interface MatchEvent {
    data class BattleStarted(val myTurn: Boolean) : MatchEvent
    data class Fired(val at: Coord) : MatchEvent
    data class OurShot(val at: Coord, val hit: Boolean, val sunk: ShipType?, val won: Boolean) : MatchEvent
    data class TheirShot(val at: Coord, val hit: Boolean, val sunk: ShipType?, val lost: Boolean) : MatchEvent
    data object RematchStarted : MatchEvent
}

/**
 * Runs one side of a match over a [Link] (see [Msg] for the conversation). Each tablet only ever knows its own fleet; the
 * opponent's ships are learned one reported shot at a time and checked with [FairPlay] at the end.
 *
 * All calls and the incoming messages run on [scope], which must be single-threaded (the main thread in the app).
 */
class MatchSession(
    private val link: Link,
    val isHost: Boolean,
    myName: String,
    bonusShot: Boolean,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(MatchState(isHost, myName, bonusShot))
    val state: StateFlow<MatchState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MatchEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<MatchEvent> = _events.asSharedFlow()

    private var salt = ""
    private var opponentCommitment: String? = null
    /** Host only: whether the host fires first next round. Null means toss a coin; after a game the loser starts. */
    private var hostFirstNext: Boolean? = null
    private var leaving = false

    init {
        send(Msg.Hello(APP_ID, PROTOCOL_VERSION, myName, bonusShot))
        scope.launch {
            try {
                for (line in link.incoming) Msg.decode(line)?.let(::handle)
            } finally {
                if (!leaving) dropped("The connection to ${opponentOr("the other device")} was lost.")
            }
        }
        scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (_state.value.phase == Phase.HANDSHAKE) {
                dropped("The other device didn't answer. Is it running Fleet Strike?")
                link.close()
            }
        }
    }

    /** Locks in our fleet. Only its fingerprint is sent; the layout stays on this tablet until the game ends. */
    fun commitFleet(fleet: Fleet): Boolean {
        val s = _state.value
        if (s.phase != Phase.PLACEMENT || s.myReady || !fleet.isComplete || s.disconnected != null) return false
        salt = FairPlay.newSalt()
        _state.update { it.copy(fleet = fleet, myReady = true) }
        send(Msg.Ready(FairPlay.commitment(fleet, salt)))
        maybeStart()
        return true
    }

    fun fire(at: Coord): Boolean {
        val s = _state.value
        if (!s.canFire || !at.inBounds || at in s.myShots) return false
        _state.update { it.copy(aiming = at) }
        send(Msg.Fire(at.row, at.col))
        _events.tryEmit(MatchEvent.Fired(at))
        return true
    }

    fun requestRematch() {
        val s = _state.value
        if (s.phase != Phase.GAME_OVER || s.iWantRematch || s.disconnected != null) return
        _state.update { it.copy(iWantRematch = true) }
        send(Msg.Rematch)
        maybeRestart()
    }

    fun leave() {
        if (leaving) return
        leaving = true
        send(Msg.Bye)
        link.close()
    }

    // ---------------------------------------------------------------- incoming

    private fun handle(msg: Msg) {
        val s = _state.value
        if (s.disconnected != null) return
        when (msg) {
            is Msg.Hello -> onHello(msg)
            is Msg.Ready -> if (s.phase == Phase.PLACEMENT && !s.opponentReady) {
                opponentCommitment = msg.commitment
                _state.update { it.copy(opponentReady = true) }
                maybeStart()
            }
            is Msg.Start -> if (!isHost && s.phase == Phase.PLACEMENT && s.myReady) beginBattle(myTurn = !msg.hostFirst)
            is Msg.Fire -> onFire(Coord(msg.row, msg.col))
            is Msg.Result -> onResult(msg)
            is Msg.Reveal -> onReveal(msg)
            Msg.Rematch -> if (s.phase == Phase.GAME_OVER) {
                _state.update { it.copy(opponentWantsRematch = true) }
                maybeRestart()
            }
            Msg.Bye -> {
                dropped("${opponentOr("The other player")} left the game.")
                link.close()
            }
        }
    }

    private fun onHello(msg: Msg.Hello) {
        if (msg.app != APP_ID || msg.version != PROTOCOL_VERSION) {
            dropped("The other device has a different version of Fleet Strike. Install the same version on both.")
            link.close()
            return
        }
        _state.update { s ->
            if (s.phase != Phase.HANDSHAKE) s
            else s.copy(
                phase = Phase.PLACEMENT,
                opponentName = msg.name.trim().take(MAX_NAME).ifEmpty { "Opponent" },
                bonusShot = if (isHost) s.bonusShot else msg.bonusShot,
            )
        }
    }

    private fun maybeStart() {
        val s = _state.value
        if (!isHost || s.phase != Phase.PLACEMENT || !s.myReady || !s.opponentReady) return
        val hostFirst = hostFirstNext ?: random.nextBoolean()
        send(Msg.Start(hostFirst))
        beginBattle(myTurn = hostFirst)
    }

    private fun beginBattle(myTurn: Boolean) {
        _state.update { it.copy(phase = Phase.BATTLE, myTurn = myTurn) }
        _events.tryEmit(MatchEvent.BattleStarted(myTurn))
    }

    private fun onFire(at: Coord) {
        val s = _state.value
        val fleet = s.fleet ?: return
        if (s.phase != Phase.BATTLE || s.myTurn || !at.inBounds) return
        val report = fleet.resolve(at, s.shotsAtMe.filterValues { it }.keys)
        send(Msg.Result(at.row, at.col, report.hit, report.sunk?.encode(), report.defeated))
        val theyGoAgain = report.hit && s.bonusShot
        _state.update {
            it.copy(
                shotsAtMe = it.shotsAtMe + (at to report.hit),
                lastShotAtMe = at,
                mySunk = if (report.sunk != null) it.mySunk + report.sunk.type else it.mySunk,
                myTurn = !theyGoAgain && !report.defeated,
                phase = if (report.defeated) Phase.GAME_OVER else it.phase,
                won = if (report.defeated) false else it.won,
                losses = if (report.defeated) it.losses + 1 else it.losses,
            )
        }
        _events.tryEmit(MatchEvent.TheirShot(at, report.hit, report.sunk?.type, report.defeated))
        if (report.defeated) gameOver(iWon = false)
    }

    private fun onResult(msg: Msg.Result) {
        val s = _state.value
        val at = Coord(msg.row, msg.col)
        if (s.phase != Phase.BATTLE || s.aiming != at) return
        val sunk = msg.sunk?.let(Placement::decode)?.takeIf { p -> s.enemySunk.none { it.type == p.type } }
        val again = msg.hit && s.bonusShot && !msg.defeated
        _state.update {
            it.copy(
                myShots = it.myShots + (at to msg.hit),
                lastMyShot = at,
                enemySunk = if (sunk != null) it.enemySunk + sunk else it.enemySunk,
                aiming = null,
                myTurn = again,
                phase = if (msg.defeated) Phase.GAME_OVER else it.phase,
                won = if (msg.defeated) true else it.won,
                wins = if (msg.defeated) it.wins + 1 else it.wins,
            )
        }
        _events.tryEmit(MatchEvent.OurShot(at, msg.hit, sunk?.type, msg.defeated))
        if (msg.defeated) gameOver(iWon = true)
    }

    private fun gameOver(iWon: Boolean) {
        val hostWon = if (isHost) iWon else !iWon
        hostFirstNext = !hostWon
        _state.value.fleet?.let { send(Msg.Reveal(it.encode(), salt)) }
    }

    private fun onReveal(msg: Msg.Reveal) {
        val s = _state.value
        if (s.phase != Phase.GAME_OVER || s.verdict != Verdict.PENDING) return
        val fleet = Fleet.decode(msg.fleet)?.takeIf { it.isComplete }
        val problem = FairPlay.check(fleet, msg.salt, opponentCommitment, s.myShots, s.enemySunk)
        _state.update {
            it.copy(
                enemyFleet = fleet,
                verdict = if (problem == null) Verdict.FAIR else Verdict.SUSPICIOUS,
                verdictNote = problem,
            )
        }
    }

    private fun maybeRestart() {
        val s = _state.value
        if (!s.iWantRematch || !s.opponentWantsRematch) return
        salt = ""
        opponentCommitment = null
        _state.value = MatchState(
            isHost = isHost,
            myName = s.myName,
            bonusShot = s.bonusShot,
            phase = Phase.PLACEMENT,
            round = s.round + 1,
            opponentName = s.opponentName,
            wins = s.wins,
            losses = s.losses,
        )
        _events.tryEmit(MatchEvent.RematchStarted)
    }

    // ---------------------------------------------------------------- plumbing

    private fun send(msg: Msg) = link.send(Msg.encode(msg))

    private fun dropped(reason: String) {
        _state.update { if (it.disconnected != null) it else it.copy(disconnected = reason, aiming = null) }
    }

    private fun opponentOr(fallback: String) = _state.value.opponentName.ifEmpty { fallback }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val MAX_NAME = 20
    }
}
