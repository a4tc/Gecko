package app.geckopresence.host.core

import java.nio.charset.StandardCharsets
import kotlin.random.Random

fun main() {
    val random = Random(0x6EC0)
    repeat(25_000) { index ->
        val textLength = random.nextInt(2, 700)
        val text = buildString(textLength) {
            repeat(textLength) { append(if (random.nextBoolean()) 'x' else '界') }
        }
        val activity = PresenceActivity(
            name = "Gecko $index",
            details = text.take(128),
            state = "Fuzz",
            largeImage = if (index % 2 == 0) PresenceAsset("https://example.com/$index.png") else null,
        )
        val envelope = DiscordGatewayProtocol.presenceUpdate(activity, 1569772369344602173L)
        val encoded = GatewayTestJson.encode(envelope)
        check(encoded.toByteArray(StandardCharsets.UTF_8).size <= DiscordGatewayProtocol.MAX_PAYLOAD_BYTES)
        check(DiscordGatewayProtocol.payloadFitsLimit(encoded))
    }
    repeat(10_000) {
        val delay = GatewayInvalidSessionPolicy.sampleRetryDelay { from, until -> random.nextLong(from, until) }
        check(delay in 1_000L..5_000L)
    }
    println("GatewayFuzzSelfTest: 25,000 payload and 10,000 retry-window cases passed")
}

/** Minimal deterministic encoder for the protocol's JSON-compatible tree used by core tests. */
private object GatewayTestJson {
    fun encode(envelope: GatewayEnvelope): String = "{\"op\":${envelope.op},\"d\":${value(envelope.data)}}"
    private fun value(value: Any?): String = when (value) {
        null, GatewayJsonNull -> "null"
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${value(it.value)}" }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { value(it) }
        else -> "null"
    }
}
