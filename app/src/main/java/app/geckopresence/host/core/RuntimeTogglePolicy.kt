package app.geckopresence.host.core

/** The runtime is ON for the full engine lifetime, even while Discord is reconnecting. */
object RuntimeTogglePolicy {
    fun isOn(engineState: String?): Boolean = engineState == "RUNNING"
}
