package org.mpmc.agent

class Backoff(private val delaysMs: List<Long> = DEFAULT_DELAYS) {
    private var index = 0

    fun nextDelayMs(): Long? {
        if (index >= delaysMs.size) return null
        return delaysMs[index++]
    }

    fun reset() {
        index = 0
    }

    companion object {
        val DEFAULT_DELAYS = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
    }
}
