package com.example.fleetstrike.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** A game another tablet is hosting on the same Wi-Fi, as heard from its beacon. */
data class WifiGame(val name: String, val ip: String, val port: Int)

/**
 * Wi-Fi play. The host listens on a TCP port and, once a second, broadcasts a small UDP beacon ("fleetstrike|1|port|name")
 * to the local network so joining tablets can list it. Both tablets must be on the same Wi-Fi (or one can be the other's
 * hotspot). If a router blocks broadcasts, the joining player can type in the host's address instead.
 */
object Wifi {
    const val TCP_PORT = 47811
    const val BEACON_PORT = 47812
    private const val STALE_MS = 4000L

    /** Waits for one player to connect, announcing the game until they do. [onListening] gets the port in use. */
    suspend fun host(name: String, onListening: (port: Int) -> Unit): Link = coroutineScope {
        val server = openServer()
        onListening(server.localPort)
        val beacon = launch(Dispatchers.IO) { broadcast(beaconText(server.localPort, name)) }
        try {
            val socket = blockingCall(server) { server.accept() }
            linkFor(socket)
        } finally {
            beacon.cancel()
            try {
                server.close()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun connect(ip: String, port: Int): Link {
        val socket = Socket()
        return blockingCall(socket) {
            socket.connect(InetSocketAddress(ip, port), 5000)
            linkFor(socket)
        }
    }

    /** Games heard on the local network, refreshed about once a second for as long as it's collected. */
    fun discover(): Flow<List<WifiGame>> = flow {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 500
            bind(InetSocketAddress(BEACON_PORT))
        }
        val heard = HashMap<String, Pair<WifiGame, Long>>()
        val buffer = ByteArray(512)
        var lastSent: List<WifiGame>? = null
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val ip = packet.address.hostAddress
                    val game = ip?.let { parseBeacon(String(packet.data, 0, packet.length, Charsets.UTF_8), it) }
                    if (game != null) heard["${game.ip}:${game.port}"] = game to System.currentTimeMillis()
                } catch (_: SocketTimeoutException) {
                }
                val now = System.currentTimeMillis()
                heard.values.removeAll { now - it.second > STALE_MS }
                val games = heard.values.map { it.first }.sortedBy { it.name.lowercase() }
                if (games != lastSent) {
                    emit(games)
                    lastSent = games
                }
            }
        } finally {
            socket.close()
        }
    }.flowOn(Dispatchers.IO)

    /** This tablet's address on the local network, for typing in by hand when discovery doesn't work. */
    fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.interfaceAddresses.map { it.address } }
            .filterIsInstance<Inet4Address>()
            .sortedByDescending { it.isSiteLocalAddress }
            .firstOrNull()?.hostAddress
    } catch (_: Exception) {
        null
    }

    fun parseBeacon(text: String, ip: String): WifiGame? {
        val parts = text.split('|')
        if (parts.size < 4 || parts[0] != APP_ID || parts[1].toIntOrNull() != PROTOCOL_VERSION) return null
        val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return WifiGame(parts.drop(3).joinToString("|").ifBlank { "Player" }, ip, port)
    }

    fun beaconText(port: Int, name: String) = "$APP_ID|$PROTOCOL_VERSION|$port|$name"

    private fun openServer(): ServerSocket = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(TCP_PORT))
        }
    } catch (_: IOException) {
        // The usual port is taken (another copy of the game still closing down?), so let the system pick one.
        ServerSocket(0)
    }

    private fun linkFor(socket: Socket): Link {
        socket.tcpNoDelay = true
        return StreamLink(socket.getInputStream(), socket.getOutputStream()) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun broadcast(text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            while (currentCoroutineContext().isActive) {
                for (target in broadcastTargets()) {
                    try {
                        socket.send(DatagramPacket(data, data.size, target, BEACON_PORT))
                    } catch (_: Exception) {
                    }
                }
                delay(1000)
            }
        }
    }

    /** The all-networks broadcast address plus each active network's own one (some Android builds only pass the latter). */
    private fun broadcastTargets(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let(out::add)
            }
        } catch (_: Exception) {
        }
        return out
    }
}
