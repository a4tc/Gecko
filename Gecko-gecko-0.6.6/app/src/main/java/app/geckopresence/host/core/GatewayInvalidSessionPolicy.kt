package app.geckopresence.host.core

/** Discord asks clients to wait a randomized one-to-five seconds after INVALID_SESSION. */
object GatewayInvalidSessionPolicy {
    const val MIN_RETRY_DELAY_MS = 1_000L
    const val MAX_RETRY_DELAY_MS = 5_000L

    fun sampleRetryDelay(nextLong: (fromInclusive: Long, untilExclusive: Long) -> Long): Long {
        val sampled = nextLong(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS + 1L)
        require(sampled in MIN_RETRY_DELAY_MS..MAX_RETRY_DELAY_MS) {
            "Retry delay sampler returned a value outside the Gateway retry window"
        }
        return sampled
    }
}
