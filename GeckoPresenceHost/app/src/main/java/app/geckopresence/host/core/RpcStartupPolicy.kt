package app.geckopresence.host.core

enum class RpcCommand { NONE, CONNECT, DISCONNECT }

/** Only explicit Developer Console commands may initialize or tear down native Discord RPC. */
object RpcStartupPolicy {
    const val ACTION_CONNECT_DISCORD = "app.geckopresence.host.action.CONNECT_DISCORD"
    const val ACTION_DISCONNECT_DISCORD = "app.geckopresence.host.action.DISCONNECT_DISCORD"

    fun commandFor(action: String?): RpcCommand = when (action) {
        ACTION_CONNECT_DISCORD -> RpcCommand.CONNECT
        ACTION_DISCONNECT_DISCORD -> RpcCommand.DISCONNECT
        else -> RpcCommand.NONE
    }
}
