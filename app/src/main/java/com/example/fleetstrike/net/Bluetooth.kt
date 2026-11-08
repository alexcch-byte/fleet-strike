package com.example.fleetstrike.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/** A tablet seen over Bluetooth. */
data class BtPeer(val name: String, val address: String, val device: BluetoothDevice)

/**
 * Bluetooth play over classic RFCOMM sockets. The host offers a service under [SERVICE_UUID]; the joining tablet picks the
 * host from its paired devices or from a scan and connects to that service.
 */
@SuppressLint("MissingPermission")
object Bluetooth {
    val SERVICE_UUID: UUID = UUID.fromString("7d3f6b2e-9c41-4e8a-b5d2-0f6a1c3e8b57")
    private const val SERVICE_NAME = "FleetStrike"

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isAvailable(context: Context) = adapter(context) != null

    fun isEnabled(context: Context) = try {
        adapter(context)?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    /** Needed to host (and to be made visible). Android 11 and older ask for nothing at run time. */
    fun hostPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

    /** Needed to look for a hosting tablet and connect to it. Before Android 12 a scan counts as a location request. */
    fun joinPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(context: Context, perms: Array<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun localName(context: Context): String = try {
        adapter(context)?.name ?: Build.MODEL
    } catch (_: SecurityException) {
        Build.MODEL
    }

    fun pairedPeers(context: Context): List<BtPeer> = try {
        adapter(context)?.bondedDevices.orEmpty().filter(::couldBeTablet).map(::peer).sortedBy { it.name.lowercase() }
    } catch (_: SecurityException) {
        emptyList()
    }

    fun peer(device: BluetoothDevice): BtPeer {
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        return BtPeer(name ?: device.address, device.address, device)
    }

    /** Leaves out headphones, speakers, watches and the like, which can never be hosting a game. */
    fun couldBeTablet(device: BluetoothDevice): Boolean {
        val major = try {
            device.bluetoothClass?.majorDeviceClass
        } catch (_: SecurityException) {
            null
        } ?: return true
        return major !in setOf(
            BluetoothClass.Device.Major.AUDIO_VIDEO,
            BluetoothClass.Device.Major.PERIPHERAL,
            BluetoothClass.Device.Major.WEARABLE,
            BluetoothClass.Device.Major.HEALTH,
            BluetoothClass.Device.Major.IMAGING,
            BluetoothClass.Device.Major.TOY,
        )
    }

    /**
     * Waits for one player. Listens on an insecure and a secure RFCOMM socket at once: the insecure one connects without the
     * tablets having to be paired first, and the secure one covers devices that insist on pairing.
     */
    suspend fun host(context: Context): Link = coroutineScope {
        val adapter = adapter(context) ?: throw IOException("This device has no Bluetooth.")
        val servers = listOfNotNull(
            runCatching { adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID) }.getOrNull(),
            runCatching { adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID) }.getOrNull(),
        )
        if (servers.isEmpty()) throw IOException("Bluetooth wouldn't start listening. Is it switched on?")
        val accepted = CompletableDeferred<BluetoothSocket>()
        try {
            val waiters = servers.map { server ->
                launch(Dispatchers.IO) {
                    try {
                        val socket = server.accept()
                        if (!accepted.complete(socket)) socket.close()
                    } catch (_: IOException) {
                    }
                }
            }
            launch {
                waiters.joinAll()
                accepted.completeExceptionally(IOException("Bluetooth stopped listening."))
            }
            linkFor(accepted.await())
        } finally {
            servers.forEach { closeQuietly(it) }
        }
    }

    /**
     * Connects to a hosting tablet. Tries the normal service lookups first, then RFCOMM channel 1 directly, which gets
     * around tablets whose service lookup is unreliable.
     */
    @SuppressLint("DiscouragedPrivateApi")
    suspend fun connect(context: Context, device: BluetoothDevice): Link {
        try {
            adapter(context)?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        // Connecting while a scan is still winding down is a common cause of "read failed, socket might closed".
        delay(250)

        fun channelOne(secure: Boolean): BluetoothSocket {
            val method = device.javaClass.getMethod(if (secure) "createRfcommSocket" else "createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            return method.invoke(device, 1) as BluetoothSocket
        }

        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID) },
            { device.createRfcommSocketToServiceRecord(SERVICE_UUID) },
            { channelOne(false) },
            { channelOne(true) },
        )
        var lastError: Exception? = null
        for (make in attempts) {
            val socket = try {
                make()
            } catch (e: Exception) {
                lastError = e
                continue
            }
            try {
                blockingCall(socket) { socket.connect() }
                return linkFor(socket)
            } catch (e: IOException) {
                closeQuietly(socket)
                lastError = e
                delay(300)
            }
        }
        throw IOException("Couldn't connect to ${peer(device).name}.", lastError)
    }

    private fun linkFor(socket: BluetoothSocket): Link =
        StreamLink(socket.inputStream, socket.outputStream) { closeQuietly(socket) }

    private fun closeQuietly(c: java.io.Closeable) {
        try {
            c.close()
        } catch (_: Exception) {
        }
    }
}

/** Looks for nearby Bluetooth devices. The caller must already hold [Bluetooth.joinPermissions]. */
@SuppressLint("MissingPermission")
class BluetoothScanner(
    private val context: Context,
    private val onFound: (BtPeer) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && Bluetooth.couldBeTablet(device)) onFound(Bluetooth.peer(device))
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()
            }
        }
    }

    fun start(): Boolean {
        val adapter = Bluetooth.adapter(context) ?: return false
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        return try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (_: SecurityException) {
            false
        }
    }

    fun stop() {
        try {
            Bluetooth.adapter(context)?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            registered = false
        }
    }
}
