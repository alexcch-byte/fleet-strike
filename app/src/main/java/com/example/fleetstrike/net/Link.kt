package com.example.fleetstrike.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A two-way connection that carries one line of text per message. Wi-Fi, Bluetooth and the practice computer all end up as
 * a [Link], so the game never knows which one it is using.
 */
interface Link {
    /** Every line the other side sends, in order. The channel closes when the connection ends. */
    val incoming: ReceiveChannel<String>

    /** Queues a line; never blocks. Lines sent after the link has closed are dropped. */
    fun send(line: String)

    /** Sends whatever is already queued, then hangs up. */
    fun close()
}

/**
 * A [Link] over a socket's streams. Reading and writing each get their own thread. The writer sends a heartbeat when
 * there's nothing else to say and hangs up if the other side has gone quiet for too long, so a tablet that walks out of
 * range or loses Wi-Fi is noticed within a few seconds instead of never.
 */
class StreamLink(input: InputStream, private val output: OutputStream, private val onClose: () -> Unit) : Link {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val outbox = LinkedBlockingQueue<String>()
    private val hungUp = AtomicBoolean(false)
    @Volatile private var closing = false
    @Volatile private var lastHeard = System.currentTimeMillis()

    override val incoming: ReceiveChannel<String> get() = inbox

    init {
        thread(name = "link-read", isDaemon = true) {
            try {
                val reader = input.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    lastHeard = System.currentTimeMillis()
                    if (line != HEARTBEAT) inbox.trySend(line)
                }
            } catch (_: Exception) {
            }
            hangUp()
        }
        thread(name = "link-write", isDaemon = true) {
            try {
                while (true) {
                    val line = outbox.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
                    if (line === END) break
                    if (System.currentTimeMillis() - lastHeard > SILENCE_LIMIT_MS) break
                    output.write(((line ?: HEARTBEAT) + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (_: Exception) {
            }
            hangUp()
        }
    }

    override fun send(line: String) {
        if (!closing) outbox.add(line)
    }

    override fun close() {
        if (closing) return
        closing = true
        outbox.add(END)
    }

    private fun hangUp() {
        if (!hungUp.compareAndSet(false, true)) return
        closing = true
        outbox.add(END)
        inbox.close()
        try {
            onClose()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val HEARTBEAT_MS = 1500L
        const val SILENCE_LIMIT_MS = 10_000L
        private const val HEARTBEAT = "~"
        /** Told to the writer thread to stop. Compared by identity, so no real message can be mistaken for it. */
        private val END = String(charArrayOf('\u0000'))
    }
}

/** Two [Link]s joined back to back in memory: what one sends, the other receives. Used for practice games and tests. */
class PipeLink private constructor(private val inbox: Channel<String>, private val outbox: Channel<String>) : Link {
    override val incoming: ReceiveChannel<String> get() = inbox

    override fun send(line: String) {
        outbox.trySend(line)
    }

    override fun close() {
        outbox.close()
        inbox.close()
    }

    companion object {
        fun pair(): Pair<Link, Link> {
            val a = Channel<String>(Channel.UNLIMITED)
            val b = Channel<String>(Channel.UNLIMITED)
            return PipeLink(a, b) to PipeLink(b, a)
        }
    }
}

/**
 * Runs a blocking call (accept, connect) on the IO pool. If the coroutine is cancelled meanwhile, [resource] is closed so
 * the call gives up straight away instead of holding the screen up until it times out.
 */
suspend fun <T> blockingCall(resource: Closeable, call: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) try {
                resource.close()
            } catch (_: Exception) {
            }
        }
    }
    try {
        withContext(Dispatchers.IO) { call() }
    } finally {
        finished.set(true)
        guard.cancel()
    }
}
