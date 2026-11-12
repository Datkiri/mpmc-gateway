package org.mpmc.protocol

import java.security.SecureRandom

object RoomCode {
    const val LENGTH = 8
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun generateUnique(attempts: Int = 5, isTaken: (String) -> Boolean): String {
        repeat(attempts) {
            val code = generate()
            if (!isTaken(code)) return code
        }
        throw IllegalStateException("could not allocate a unique room code in $attempts attempts")
    }

    fun normalize(input: String): String? {
        val mapped = buildString(input.length) {
            for (ch in input.uppercase()) {
                when (ch) {
                    '-', ' ' -> Unit
                    'I', 'L' -> append('1')
                    'O' -> append('0')
                    'U' -> append('V')
                    else -> append(ch)
                }
            }
        }
        if (mapped.length != LENGTH) return null
        if (mapped.any { it !in ALPHABET }) return null
        return mapped
    }

    fun isValid(input: String): Boolean = normalize(input) != null

    fun pretty(code: String): String {
        require(code.length == LENGTH) { "room code must be $LENGTH characters" }
        return "${code.substring(0, 4)}-${code.substring(4)}"
    }
}
