from pathlib import Path

files = {
    "protocol": Path("app/src/main/java/app/geckopresence/host/core/DiscordGatewayProtocol.kt"),
    "publisher": Path("app/src/main/java/app/geckopresence/host/DiscordGatewayPublisher.kt"),
    "test": Path("tests/DiscordGatewayProtocolSelfTest.kt"),
    "service": Path("app/src/main/java/app/geckopresence/host/PresenceEngineService.kt"),
    "docs": Path("docs/DISCORD_GATEWAY.md"),
    "readme": Path("README.md"),
}

src = {k: p.read_text() for k, p in files.items()}

def sub(key, old, new, label):
    count = src[key].count(old)
    if count != 1:
        raise SystemExit(f"ABORT {label}: expected 1 match, got {count}")
    src[key] = src[key].replace(old, new, 1)

# 1. Protocol: Android-compatible minimal payload + clear.
sub("protocol",
    'const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"',
    'const val GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"',
    "Gateway URL")
sub("protocol", '"capabilities" to 65',
    '"capabilities" to 16381', "Identify capabilities")
sub("protocol", '"browser" to "Discord Client"',
    '"browser" to "Discord Android"', "Identify browser")
sub("protocol",
    'fun presenceUpdate(activity: PresenceActivity?, applicationId: Long, status: String = "online"): GatewayEnvelope {',
    'fun presenceUpdate(activity: PresenceActivity?, applicationId: Long, status: String = "online", nowMillis: Long = System.currentTimeMillis()): GatewayEnvelope {',
    "Presence signature")
sub("protocol",
    '"since" to GatewayJsonNull,\n                "activities" to activities,\n                "status" to status,\n                "afk" to false,',
    '"since" to if (activity == null) 0L else nowMillis,\n                "activities" to activities,\n                "status" to status,\n                "afk" to (activity == null),',
    "Presence since/afk")
sub("protocol",
    '        put("type", type.wireValue)\n        details?',
    '        put("type", type.wireValue)\n        put("status_display_type", 0)\n        details?',
    "Activity display type")
sub("protocol",
    '        if (applicationId > 0L) put("application_id", applicationId.toString())',
    '        if (applicationId > 0L && (largeImage != null || smallImage != null || buttons.isNotEmpty())) {\n            put("application_id", applicationId.toString())\n        }',
    "Conditional application ID")
sub("protocol",
    '        if (buttons.isNotEmpty()) {\n            put("buttons", buttons.map { button ->\n                linkedMapOf("label" to button.label, "url" to button.url)\n            })\n        }',
    '        if (buttons.isNotEmpty()) {\n            put("buttons", buttons.map(PresenceButton::label))\n            put("metadata", mapOf("button_urls" to buttons.map(PresenceButton::url)))\n        }',
    "Button payload")

# 2. Publisher: Android handshake headers and Identify.
sub("publisher",
    'package app.geckopresence.host\n\n',
    'package app.geckopresence.host\n\nimport android.os.Build\n',
    "Android Build import")
sub("publisher",
    'import io.ktor.client.engine.cio.CIO\n',
    'import io.ktor.client.engine.cio.CIO\nimport io.ktor.client.request.header\n',
    "Ktor header import")
sub("publisher",
    '    fun publish(activity: PresenceActivity?, status: String = "online") {\n        synchronized(stateLock) {\n            pendingActivity = activity\n            pendingStatus = status.takeIf { it in ALLOWED_PRESENCE_STATUSES } ?: "online"\n            pendingRevision += 1L\n        }\n        updateEvents.trySend(Unit)\n    }',
    '    fun publish(activity: PresenceActivity?, status: String = "online") {\n        val revision = synchronized(stateLock) {\n            pendingActivity = activity\n            pendingStatus = status.takeIf { it in ALLOWED_PRESENCE_STATUSES } ?: "online"\n            ++pendingRevision\n        }\n        onDiagnostic(if (activity == null) "Presence clear queued revision=$revision" else "Presence queued revision=$revision")\n        updateEvents.trySend(Unit)\n    }',
    "Presence queue diagnostics")
sub("publisher",
    '                    client.webSocketSession(urlString = targetUrl)\n',
    '                    client.webSocketSession(urlString = targetUrl) {\n                        header("User-Agent", "Discord-Android/314013;RNA")\n                        header("Accept-Language", "en-US")\n                        header("Cache-Control", "no-cache")\n                        header("Pragma", "no-cache")\n                    }\n',
    "Android WebSocket headers")

old = 'DiscordGatewayProtocol.identify(token, "Windows", "ktor")'
if src["publisher"].count(old) != 2:
    raise SystemExit("ABORT Identify calls: expected 2 matches")
src["publisher"] = src["publisher"].replace(
    old, 'DiscordGatewayProtocol.identify(token, "Android", Build.DEVICE)')

sub("publisher",
    '                                "RESUMED" -> {\n                                    isReady = true\n                                    retryDelayMs = 1_000L',
    '                                "RESUMED" -> {\n                                    isReady = true\n                                    // Replay the latest snapshot after session resume.\n                                    lastSentRevision = -1L\n                                    lastPresenceUpdateAt = 0L\n                                    retryDelayMs = 1_000L',
    "RESUMED replay")
sub("publisher",
    '                receivedCloseCode = currentSocket.closeReason.await()?.code?.toInt()',
    '                receivedCloseCode = currentSocket.closeReason.await()?.code?.toInt()\n                onDiagnostic("Gateway socket closed code=${receivedCloseCode ?: "unknown"}")',
    "Socket close diagnostic")
sub("publisher",
    '        } catch (_: Exception) {\n            socket?.let { requestReconnect(it, "Gateway presence send failed") }\n            false\n        }',
    '        } catch (exception: Exception) {\n            onDiagnostic("Presence opcode-3 write failed (${exception.javaClass.simpleName})")\n            socket?.let { requestReconnect(it, "Gateway presence send failed") }\n            false\n        }',
    "Presence write failure diagnostic")
sub("publisher",
    '        if (!DiscordGatewayProtocol.payloadFitsLimit(payload)) throw GatewayPayloadTooLargeException()\n        currentSocket.send(Frame.Text(payload))\n        true',
    '        if (!DiscordGatewayProtocol.payloadFitsLimit(payload)) throw GatewayPayloadTooLargeException()\n        if (envelope.op == DiscordGatewayProtocol.OP_PRESENCE_UPDATE) {\n            onDiagnostic("Presence opcode-3 attempt bytes=${payload.toByteArray(Charsets.UTF_8).size}")\n        }\n        currentSocket.send(Frame.Text(payload))\n        if (envelope.op == DiscordGatewayProtocol.OP_PRESENCE_UPDATE) {\n            onDiagnostic("Presence opcode-3 write completed (local socket)")\n        }\n        true',
    "Presence send diagnostics")
sub("publisher",
    '        return "wss://${uri.rawAuthority}/?v=10&encoding=json"',
    '        return "wss://${uri.rawAuthority}/?v=9&encoding=json"',
    "Resume Gateway version")

# 3. Protocol tests: minimal, rich, clear, Android Identify.
sub("test", 'operatingSystem = "Windows"', 'operatingSystem = "Android"', "Test Identify OS")
sub("test", 'device = "ktor"', 'device = "SM-A047F"', "Test Identify device")
sub("test", 'check(identifyData["capabilities"] == 65)',
    'check(identifyData["capabilities"] == 16381)', "Test capabilities")
sub("test", 'check(properties?.get("os") == "Windows")',
    'check(properties?.get("os") == "Android")', "Test OS property")
sub("test", 'check(properties?.get("browser") == "Discord Client")',
    'check(properties?.get("browser") == "Discord Android")', "Test browser property")
sub("test", 'check(properties?.get("device") == "ktor")',
    'check(properties?.get("device") == "SM-A047F")', "Test device property")
sub("test",
    '        val update = DiscordGatewayProtocol.presenceUpdate(activity, 1569772369344602173L)',
    '        val update = DiscordGatewayProtocol.presenceUpdate(activity, 1569772369344602173L, nowMillis = 1_700_000_000_000L)',
    "Deterministic presence test")
sub("test", 'check(data["since"] === GatewayJsonNull)',
    'check(data["since"] == 1_700_000_000_000L)', "Active since")
sub("test",
    '        check(wireActivity["type"] == 3)\n',
    '        check(wireActivity["type"] == 3)\n        check(wireActivity["status_display_type"] == 0)\n',
    "Display type test")
sub("test",
    '        val buttons = wireActivity["buttons"] as List<*>\n        val button = buttons.single() as Map<*, *>\n        check(button["label"] == "Open source")\n        check(button["url"] == "https://example.com")',
    '        val buttons = wireActivity["buttons"] as List<*>\n        check(buttons == listOf("Open source"))\n        val metadata = wireActivity["metadata"] as Map<*, *>\n        check(metadata["button_urls"] == listOf("https://example.com"))',
    "Button metadata test")
sub("test",
    '        val cleared = DiscordGatewayProtocol.presenceUpdate(null, 1569772369344602173L)\n        check(((cleared.data as Map<*, *>)["activities"] as List<*>).isEmpty())',
    '        val clearedData = DiscordGatewayProtocol.presenceUpdate(null, 1569772369344602173L).data as Map<*, *>\n        check((clearedData["activities"] as List<*>).isEmpty())\n        check(clearedData["since"] == 0L)\n        check(clearedData["afk"] == true)\n\n        val minimalData = DiscordGatewayProtocol.presenceUpdate(\n            PresenceActivity(name = "Firefox"), 1569772369344602173L, nowMillis = 123L,\n        ).data as Map<*, *>\n        val minimalActivity = (minimalData["activities"] as List<*>).single() as Map<*, *>\n        check(minimalActivity.keys == setOf("name", "type", "status_display_type"))\n        check("application_id" !in minimalActivity)\n        check("assets" !in minimalActivity && "buttons" !in minimalActivity && "timestamps" !in minimalActivity)',
    "Minimal and clear tests")
sub("test",
    'println("DiscordGatewayProtocolSelfTest: identify, heartbeat, resume, source mapping, full activity mapping, and clear payload passed")',
    'println("DiscordGatewayProtocolSelfTest: Android Identify, minimal/full payload, button metadata, and clear payload passed")',
    "Test summary")

# 4. Activity selection diagnostics.
sub("service",
    '    private fun applyPresenceWinner(winner: PresenceUpdate?) {\n        latestPresence = winner',
    '    private fun applyPresenceWinner(winner: PresenceUpdate?) {\n        latestPresence = winner\n        recordEvent("Presence selected: source=${winner?.sourceType ?: "none"}")',
    "Selected source diagnostic")

# 5. Documentation.
sub("docs",
    'wss://gateway.discord.gg/?v=10&encoding=json',
    'wss://gateway.discord.gg/?v=9&encoding=json',
    "Docs Gateway version")
sub("docs",
    '7. Publish Presence Update (opcode 3) from the latest winning `PresenceActivity` snapshot. Updates are conflated and throttled to avoid excess dispatches. The current minimum interval is 4.1 seconds, below the documented ceiling of five game-status updates per 20 seconds.',
    '7. On both `READY` and `RESUMED`, replay the latest queued activity. Publish Presence Update (opcode 3) from the latest winning `PresenceActivity` snapshot. Updates are conflated and throttled. A minimal activity sends only `name` and `type` plus the compatibility display field. Clearing the winner sends `activities: []`.',
    "Docs lifecycle")
sub("docs", '- up to two `{label, url}` button objects',
    '- up to two button labels in `buttons`, with matching URLs in `metadata.button_urls',
    "Docs buttons")
sub("docs",
    'Per-app profiles currently customize the activity label, details, and state. They do not yet override activity type, artwork, buttons, or timestamps independently per package.',
    'Per-app profiles can override activity type, label, details, state, stream URL, artwork, buttons, timestamps, priority, enabled state, and account status independently for each selected package.',
    "Docs profiles")
sub("docs",
    'No gallery asset is uploaded as part of the current implementation. Public external media references can be passed through the protocol model; the Discord client/media proxy must be able to resolve them. Any future gallery upload/registration feature must show the destination and obtain explicit confirmation before transmitting the selected image.',
    'Artwork has three paths: (1) Upload Discord application assets in the Developer Portal and reference the asset key in Gecko; no opaque asset ID needs to be copied. (2) Enter a public HTTPS image URL or Discord `mp:` media-proxy reference when Discord can resolve it. (3) Import to the private local gallery and explicitly confirm upload to the user-configured Imgur Client-ID; the resulting URL is public. Gecko does not automate undocumented Discord external-assets registration endpoints.',
    "Docs artwork")
sub("readme",
    'follows the documented Gateway v10 message lifecycle',
    'follows the Gateway v9 message lifecycle',
    "README Gateway version")

# All patterns passed. Write only now.
for key, path in files.items():
    path.write_text(src[key])

print("Patch applied:", len(files), "files")
