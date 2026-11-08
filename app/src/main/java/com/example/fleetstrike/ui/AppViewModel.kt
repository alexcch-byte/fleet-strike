package com.example.fleetstrike.ui

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fleetstrike.audio.SoundFx
import com.example.fleetstrike.game.Difficulty
import com.example.fleetstrike.net.Bluetooth
import com.example.fleetstrike.net.BluetoothScanner
import com.example.fleetstrike.net.BtPeer
import com.example.fleetstrike.net.Link
import com.example.fleetstrike.net.PipeLink
import com.example.fleetstrike.net.Wifi
import com.example.fleetstrike.net.WifiGame
import com.example.fleetstrike.session.BotPlayer
import com.example.fleetstrike.session.MatchSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Transport(val title: String) { WIFI("Wi-Fi"), BLUETOOTH("Bluetooth") }

sealed interface Screen {
    data object Home : Screen
    data class Lobby(val transport: Transport, val hosting: Boolean) : Screen
    data class Match(val session: MatchSession, val via: String, val bot: BotPlayer? = null) : Screen
}

/** What the "find a game" screen shows while hosting or searching. */
class LobbyState {
    var status by mutableStateOf("")
    /** How others can find this tablet: its IP on Wi-Fi, or its Bluetooth name. */
    var hostAddress by mutableStateOf<String?>(null)
    var wifiGames by mutableStateOf<List<WifiGame>>(emptyList())
    var paired by mutableStateOf<List<BtPeer>>(emptyList())
    var nearby by mutableStateOf<List<BtPeer>>(emptyList())
    var scanning by mutableStateOf(false)
    var connectingTo by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
}

/**
 * Holds everything that must outlive a screen rotation: settings, the lobby (hosting / searching on Wi-Fi or Bluetooth)
 * and the match in progress with its connection.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("fleetstrike", Context.MODE_PRIVATE)

    var playerName by mutableStateOf(prefs.getString(KEY_NAME, null) ?: "Captain ${Random.nextInt(10, 100)}")
        private set
    var bonusShot by mutableStateOf(prefs.getBoolean(KEY_BONUS, false))
        private set
    var difficulty by mutableStateOf(prefs.getString(KEY_DIFFICULTY, null)?.let { runCatching { Difficulty.valueOf(it) }.getOrNull() } ?: Difficulty.NORMAL)
        private set
    var soundOn by mutableStateOf(prefs.getBoolean(KEY_SOUND, true))
        private set

    val sound = SoundFx(app).also { it.enabled = soundOn }

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var lobby by mutableStateOf(LobbyState())
        private set

    private var lobbyJob: Job? = null
    private var searchJob: Job? = null
    private var scanner: BluetoothScanner? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ---------------------------------------------------------------- settings

    fun updateName(name: String) {
        playerName = name.take(MatchSession.MAX_NAME)
        prefs.edit { putString(KEY_NAME, playerName) }
    }

    fun updateBonusShot(on: Boolean) {
        bonusShot = on
        prefs.edit { putBoolean(KEY_BONUS, on) }
    }

    fun updateDifficulty(d: Difficulty) {
        difficulty = d
        prefs.edit { putString(KEY_DIFFICULTY, d.name) }
    }

    fun updateSound(on: Boolean) {
        soundOn = on
        sound.enabled = on
        prefs.edit { putBoolean(KEY_SOUND, on) }
    }

    private val displayName get() = playerName.trim().ifEmpty { "Captain" }

    // ---------------------------------------------------------------- lobby

    fun openLobby(transport: Transport, hosting: Boolean) {
        stopLobby()
        lobby = LobbyState()
        screen = Screen.Lobby(transport, hosting)
        if (transport == Transport.WIFI) {
            if (hosting) hostWifi() else searchWifi()
        }
        // Bluetooth starts once the screen has the permissions it needs: see hostBluetooth / searchBluetooth.
    }

    fun cancelLobby() {
        stopLobby()
        screen = Screen.Home
    }

    fun lobbyError(message: String) {
        lobby.error = message
    }

    private fun hostWifi() {
        val l = lobby
        l.status = "Waiting for another player to join…"
        lobbyJob = viewModelScope.launch {
            try {
                val link = Wifi.host(displayName) { port ->
                    l.hostAddress = Wifi.localAddress()?.let { if (port == Wifi.TCP_PORT) it else "$it:$port" }
                }
                startMatch(link, isHost = true, via = Transport.WIFI.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                l.error = "Couldn't host a Wi-Fi game (${e.message}). Check Wi-Fi is on."
            }
        }
    }

    private fun searchWifi() {
        val l = lobby
        l.status = "Looking for games on this Wi-Fi…"
        l.hostAddress = Wifi.localAddress()
        try {
            val wm = getApplication<Application>().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("fleetstrike-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        searchJob = viewModelScope.launch {
            Wifi.discover()
                .catch { l.error = "Couldn't listen for games (${it.message}). You can still type the host's address." }
                .collect { l.wifiGames = it }
        }
    }

    fun joinWifi(ip: String, port: Int, label: String) {
        val l = lobby
        if (l.connectingTo != null) return
        l.connectingTo = label
        l.error = null
        lobbyJob = viewModelScope.launch {
            try {
                val link = Wifi.connect(ip, port)
                startMatch(link, isHost = false, via = Transport.WIFI.title)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                l.error = "Couldn't reach $label. Make sure both tablets are on the same Wi-Fi and the other one is hosting."
            } finally {
                l.connectingTo = null
            }
        }
    }

    /** Accepts "192.168.1.20" or "192.168.1.20:47811". */
    fun joinWifiByAddress(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val ip = t.substringBefore(':')
        val port = t.substringAfter(':', "").toIntOrNull() ?: Wifi.TCP_PORT
        joinWifi(ip, port, ip)
    }

    fun hostBluetooth() {
        if (lobbyJob?.isActive == true) return
        val l = lobby
        val app = getApplication<Application>()
        l.status = "Waiting for another player to connect…"
        l.hostAddress = Bluetooth.localName(app)
        lobbyJob = viewModelScope.launch {
            try {
                val link = Bluetooth.host(app)
                startMatch(link, isHost = true, via = Transport.BLUETOOTH.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                l.error = e.message ?: "Couldn't host over Bluetooth."
            }
        }
    }

    fun searchBluetooth() {
        val l = lobby
        l.status = "Pick the tablet that is hosting."
        l.paired = Bluetooth.pairedPeers(getApplication())
        scanBluetooth()
    }

    fun scanBluetooth() {
        val l = lobby
        scanner?.stop()
        l.nearby = emptyList()
        val s = BluetoothScanner(
            getApplication(),
            onFound = { peer ->
                if (l.paired.none { it.address == peer.address } && l.nearby.none { it.address == peer.address }) l.nearby = l.nearby + peer
            },
            onFinished = { l.scanning = false },
        )
        scanner = s
        l.scanning = s.start()
    }

    fun joinBluetooth(peer: BtPeer) {
        val l = lobby
        if (l.connectingTo != null) return
        scanner?.stop()
        l.scanning = false
        l.connectingTo = peer.name
        l.error = null
        lobbyJob = viewModelScope.launch {
            try {
                val link = Bluetooth.connect(getApplication(), peer.device)
                startMatch(link, isHost = false, via = Transport.BLUETOOTH.title)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                l.error = "Couldn't connect to ${peer.name}. Make sure it is hosting a Bluetooth game and is close by."
            } finally {
                l.connectingTo = null
            }
        }
    }

    private fun stopLobby() {
        lobbyJob?.cancel()
        lobbyJob = null
        stopSearching()
    }

    private fun stopSearching() {
        searchJob?.cancel()
        searchJob = null
        scanner?.stop()
        scanner = null
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    // ---------------------------------------------------------------- matches

    /** Called from inside the lobby job once connected, so that job is left to finish rather than cancelled. */
    private fun startMatch(link: Link, isHost: Boolean, via: String) {
        lobbyJob = null
        stopSearching()
        val session = MatchSession(link, isHost, displayName, bonusShot, viewModelScope)
        screen = Screen.Match(session, via)
    }

    fun startPractice() {
        stopLobby()
        val (mine, theirs) = PipeLink.pair()
        val session = MatchSession(mine, isHost = true, myName = displayName, bonusShot = bonusShot, scope = viewModelScope)
        val botSession = MatchSession(theirs, isHost = false, myName = "Computer", bonusShot = false, scope = viewModelScope)
        val bot = BotPlayer(botSession, difficulty, viewModelScope)
        screen = Screen.Match(session, "Practice · ${difficulty.title}", bot)
    }

    fun leaveMatch() {
        (screen as? Screen.Match)?.let {
            it.session.leave()
            it.bot?.stop()
        }
        screen = Screen.Home
    }

    override fun onCleared() {
        (screen as? Screen.Match)?.let {
            it.session.leave()
            it.bot?.stop()
        }
        stopLobby()
        sound.release()
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_BONUS = "bonusShot"
        const val KEY_DIFFICULTY = "difficulty"
        const val KEY_SOUND = "sound"
    }
}
