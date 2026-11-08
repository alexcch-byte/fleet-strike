package com.example.fleetstrike.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Sfx { LAUNCH, SPLASH, BOOM, SINK, WIN, LOSE, CLICK }

/**
 * Game sounds, synthesised on first launch (no audio files ship with the app): written to the cache as small WAVs and
 * played through a [SoundPool] so several can overlap.
 */
class SoundFx(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sfx, Int>()
    @Volatile var enabled = true

    init {
        val dir = File(context.cacheDir, "sfx-v1").apply { mkdirs() }
        thread(name = "sfx-synth", isDaemon = true) {
            for (sfx in Sfx.entries) {
                try {
                    val file = File(dir, "${sfx.name.lowercase()}.wav")
                    if (!file.exists()) file.writeBytes(wav(synth(sfx)))
                    val id = pool.load(file.absolutePath, 1)
                    synchronized(ids) { ids[sfx] = id }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun play(sfx: Sfx, volume: Float = 0.9f) {
        if (!enabled) return
        val id = synchronized(ids) { ids[sfx] } ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() = pool.release()

    // ---------------------------------------------------------------- synthesis

    private fun synth(sfx: Sfx): FloatArray {
        val rnd = Random(sfx.ordinal + 7)
        return when (sfx) {
            Sfx.LAUNCH -> render(0.35f) { t, _ ->
                val f = 900f - 1800f * t
                0.25f * sin(2 * PI * f.coerceAtLeast(150f) * t).toFloat() * exp(-6f * t) + 0.35f * (rnd.nextFloat() * 2 - 1) * exp(-9f * t)
            }
            Sfx.SPLASH -> lowPass(render(0.6f) { t, _ ->
                val bloop = 0.5f * sin(2 * PI * (320f - 260f * t) * t).toFloat() * exp(-14f * t)
                bloop + 0.8f * (rnd.nextFloat() * 2 - 1) * exp(-7f * t) * (1f - exp(-80f * t))
            }, 0.25f)
            Sfx.BOOM -> lowPass(render(0.9f) { t, _ ->
                val thump = 0.9f * sin(2 * PI * (75f - 35f * t) * t).toFloat() * exp(-4.5f * t)
                thump + 0.9f * (rnd.nextFloat() * 2 - 1) * exp(-5f * t)
            }, 0.12f)
            Sfx.SINK -> lowPass(render(1.4f) { t, _ ->
                val groan = 0.4f * sin(2 * PI * (220f - 140f * t) * t).toFloat() * exp(-1.6f * t)
                val blast = 0.9f * (rnd.nextFloat() * 2 - 1) * exp(-3.5f * t)
                groan + blast + 0.6f * sin(2 * PI * 55f * t).toFloat() * exp(-3f * t)
            }, 0.1f)
            Sfx.WIN -> notes(listOf(523.25f, 659.25f, 783.99f, 1046.5f), 0.14f, last = 0.45f)
            Sfx.LOSE -> notes(listOf(392f, 329.63f, 261.63f, 196f), 0.2f, last = 0.6f)
            Sfx.CLICK -> render(0.05f) { t, _ -> 0.5f * sin(2 * PI * 1400f * t).toFloat() * exp(-70f * t) }
        }
    }

    private fun render(seconds: Float, f: (t: Float, i: Int) -> Float): FloatArray =
        FloatArray((seconds * RATE).toInt()) { i -> f(i / RATE.toFloat(), i) }

    private fun lowPass(samples: FloatArray, k: Float): FloatArray {
        var y = 0f
        val out = FloatArray(samples.size) { i ->
            y += k * (samples[i] - y)
            y
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-4f)
        return FloatArray(out.size) { out[it] / peak * 0.9f }
    }

    private fun notes(freqs: List<Float>, each: Float, last: Float): FloatArray {
        val parts = freqs.mapIndexed { n, f ->
            val len = if (n == freqs.lastIndex) last else each
            render(len) { t, _ ->
                val env = (1f - exp(-60f * t)) * exp(-3f * t / len)
                0.35f * env * (sin(2 * PI * f * t).toFloat() + 0.3f * sin(4 * PI * f * t).toFloat())
            }
        }
        return parts.reduce { a, b -> a + b }
    }

    private fun wav(samples: FloatArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        return header.array() + data.array()
    }

    private companion object {
        const val RATE = 22050
    }
}
