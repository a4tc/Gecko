from pathlib import Path

gateway = Path(
    "app/src/main/java/app/geckopresence/host/DiscordGatewayPublisher.kt"
)
service = Path(
    "app/src/main/java/app/geckopresence/host/PresenceEngineService.kt"
)
audit = Path("scripts/security-audit.sh")

g = gateway.read_text()
s = service.read_text()
a = audit.read_text()

# Separate Ktor transport ceiling from Gecko's 64 KiB application limit.
lines = g.splitlines()
idx = next(
    (i for i, line in enumerate(lines)
     if "private const val MAX_GATEWAY_FRAME_BYTES =" in line),
    None,
)
if idx is None:
    raise SystemExit("Gateway transport limit declaration not found")

indent = lines[idx][:len(lines[idx]) - len(lines[idx].lstrip())]
lines[idx] = indent + (
    "private const val MAX_GATEWAY_FRAME_BYTES = 8L * 1024L * 1024L"
)

accepted_idx = next(
    (i for i, line in enumerate(lines)
     if "private const val MAX_ACCEPTED_GATEWAY_FRAME_BYTES =" in line),
    None,
)
accepted_line = indent + (
    "private const val MAX_ACCEPTED_GATEWAY_FRAME_BYTES = 64L * 1024L"
)
if accepted_idx is None:
    lines.insert(idx + 1, accepted_line)
else:
    lines[accepted_idx] = accepted_line

g = "\n".join(lines) + "\n"

old = '''                    val message = frame.readText()
                    if (message.toByteArray(Charsets.UTF_8).size > MAX_GATEWAY_FRAME_BYTES) {
                        requestReconnect(currentSocket, "Gateway frame exceeded the local safety limit")
                        continue
                    }
                    val packet = try {
                        JSONObject(message)
                    } catch (_: JSONException) {
                        requestReconnect(currentSocket, "Invalid Gateway JSON")
                        continue
                    }
                    val opcode = packet.intOrDefault("op", -1)
                    val data = packet.valueOrNull("d")
'''

new = '''                    val message = frame.readText()
                    val frameBytes = message.toByteArray(Charsets.UTF_8).size.toLong()

                    val packet = try {
                        JSONObject(message)
                    } catch (_: JSONException) {
                        requestReconnect(currentSocket, "Invalid Gateway JSON")
                        continue
                    }

                    val opcode = packet.intOrDefault("op", -1)
                    val eventName = packet.stringOrDefault("t")
                    val frameEvent = eventName.take(32).ifBlank { "none" }

                    if (
                        opcode == DiscordGatewayProtocol.OP_HELLO ||
                        opcode == DiscordGatewayProtocol.OP_RECONNECT ||
                        opcode == DiscordGatewayProtocol.OP_INVALID_SESSION ||
                        eventName == "READY" ||
                        eventName == "RESUMED"
                    ) {
                        emitState(
                            STATE_IDENTIFYING,
                            "Gateway frame probe: op=$opcode event=$frameEvent bytes=$frameBytes"
                        )
                    }

                    if (frameBytes > MAX_ACCEPTED_GATEWAY_FRAME_BYTES) {
                        emitState(
                            STATE_RECONNECTING,
                            "Gateway frame cap hit: op=$opcode event=$frameEvent bytes=$frameBytes cap=$MAX_ACCEPTED_GATEWAY_FRAME_BYTES"
                        )
                        requestReconnect(currentSocket, "Gateway frame exceeded diagnostic application cap")
                        continue
                    }

                    val data = packet.valueOrNull("d")
'''

if old not in g:
    raise SystemExit("Gateway receive block did not match; no files written")

g = g.replace(old, new, 1)

old_service = '''                    gatewayState = state
                    lastStatusMessage = message
                    if (state == DiscordGatewayPublisher.STATE_CONNECTED) {'''

new_service = '''                    gatewayState = state
                    lastStatusMessage = message
                    if (
                        message.startsWith("Gateway frame probe:") ||
                        message.startsWith("Gateway frame cap hit:")
                    ) {
                        recordEvent(message)
                    }
                    if (state == DiscordGatewayPublisher.STATE_CONNECTED) {'''

if old_service not in s:
    raise SystemExit("Gateway service callback did not match; no files written")

s = s.replace(old_service, new_service, 1)

old_audit = 'require("message.toByteArray(Charsets.UTF_8).size > MAX_GATEWAY_FRAME_BYTES" in publisher, "Gateway JSON frame has an explicit UTF-8 byte limit")'
new_audit = 'require("message.toByteArray(Charsets.UTF_8).size.toLong()" in publisher and "frameBytes > MAX_ACCEPTED_GATEWAY_FRAME_BYTES" in publisher and "MAX_ACCEPTED_GATEWAY_FRAME_BYTES = 64L * 1024L" in publisher, "Gateway JSON frame is measured before the application safety cap")'

if old_audit not in a:
    raise SystemExit("Security audit assertion did not match; no files written")

a = a.replace(old_audit, new_audit, 1)

gateway.write_text(g)
service.write_text(s)
audit.write_text(a)

print("Gateway frame diagnostic probe installed.")
print("Transport ceiling: 8 MiB")
print("Application cap under test: 64 KiB")
print("Logs: opcode, event name and byte count only.")
