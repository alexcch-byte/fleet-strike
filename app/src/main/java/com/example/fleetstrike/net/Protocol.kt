package com.example.fleetstrike.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val APP_ID = "fleetstrike"
const val PROTOCOL_VERSION = 1

/**
 * Everything the two tablets say to each other, one JSON object per line.
 *
 * Both sides open with [Hello]. Each player then sends [Ready] with a fingerprint of their fleet; once the host has both it
 * sends [Start] naming who fires first. Shots go [Fire] → [Result]. When a fleet is wiped out both sides send [Reveal] so the
 * other can check the fingerprint, and either may then ask for a [Rematch].
 */
@Serializable
sealed interface Msg {
    /** [bonusShot] is the host's house rule (a hit earns another shot); the joining tablet's value is ignored. */
    @Serializable @SerialName("hello")
    data class Hello(val app: String, val version: Int, val name: String, val bonusShot: Boolean) : Msg

    @Serializable @SerialName("ready")
    data class Ready(val commitment: String) : Msg

    @Serializable @SerialName("start")
    data class Start(val hostFirst: Boolean) : Msg

    @Serializable @SerialName("fire")
    data class Fire(val row: Int, val col: Int) : Msg

    /** [sunk] is the encoded placement of the ship this shot finished off, so the shooter can draw it. */
    @Serializable @SerialName("result")
    data class Result(val row: Int, val col: Int, val hit: Boolean, val sunk: String? = null, val defeated: Boolean = false) : Msg

    @Serializable @SerialName("reveal")
    data class Reveal(val fleet: String, val salt: String) : Msg

    @Serializable @SerialName("rematch")
    data object Rematch : Msg

    @Serializable @SerialName("bye")
    data object Bye : Msg

    companion object {
        private val json = Json {
            classDiscriminator = "t"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(msg: Msg): String = json.encodeToString(serializer(), msg)

        /** Null for anything we don't understand (a newer version, or noise), which the session simply ignores. */
        fun decode(line: String): Msg? = try {
            json.decodeFromString(serializer(), line)
        } catch (_: Exception) {
            null
        }
    }
}
