package app.geckopresence.host.core

enum class RpcCommand { NONE, CONNECT, DISCONNECT }

/** Maps the single user-facing Turn On action to engine + Gateway startup. */
object RpcStartupPolicy {
    const val ACTION_TURN_ON = "app.geckopresence.host.action.TURN_ON"
    const val ACTION_CONNECT_DISCORD = "app.geckopresence.host.action.CONNECT_DISCORD"
    const val ACTION_DISCONNECT_DISCORD = "app.geckopresence.host.action.DISCONNECT_DISCORD"

    fun commandFor(action: String?): RpcCommand = when (action) {
        ACTION_TURN_ON, ACTION_CONNECT_DISCORD -> RpcCommand.CONNECT
        ACTION_DISCONNECT_DISCORD -> RpcCommand.DISCONNECT
        else -> RpcCommand.NONE
    }
}
