package app.geckopresence.host.core

enum class GatewayCloseDisposition {
    RETRY,
    RETRY_WITH_NEW_SESSION,
    BACKOFF,
    AUTHENTICATION_REJECTED,
    TERMINAL_CONFIGURATION_ERROR,
}

/** Maps documented Discord Gateway close codes to bounded client recovery behavior. */
object GatewayCloseCodePolicy {
    fun disposition(code: Int?): GatewayCloseDisposition = when (code) {
        4004 -> GatewayCloseDisposition.AUTHENTICATION_REJECTED
        4003, 4005, 4007, 4009 -> GatewayCloseDisposition.RETRY_WITH_NEW_SESSION
        4008 -> GatewayCloseDisposition.BACKOFF
        4010, 4011, 4012, 4013, 4014 -> GatewayCloseDisposition.TERMINAL_CONFIGURATION_ERROR
        else -> GatewayCloseDisposition.RETRY
    }
}
