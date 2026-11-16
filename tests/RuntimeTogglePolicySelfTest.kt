package app.geckopresence.host.core

fun main() {
    check(!RuntimeTogglePolicy.isOn("STOPPED"))
    check(!RuntimeTogglePolicy.isOn("ERROR"))
    check(!RuntimeTogglePolicy.isOn(null))
    check(RuntimeTogglePolicy.isOn("RUNNING"))
    check(RpcStartupPolicy.commandFor(RpcStartupPolicy.ACTION_TURN_ON) == RpcCommand.CONNECT)
    println("RuntimeTogglePolicySelfTest: engine lifetime state and single-action routing passed")
}
