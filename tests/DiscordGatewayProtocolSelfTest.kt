package app.geckopresence.host.core

object DiscordGatewayProtocolSelfTest {
    @JvmStatic
    fun main(args: Array<String>) = run()

    @JvmStatic
    fun run() {
        val identify = DiscordGatewayProtocol.identify(
            token = "owned-account-session-token-placeholder",
            operatingSystem = "Windows",
            device = "ktor",
        )
        check(identify.op == DiscordGatewayProtocol.OP_IDENTIFY)
        val identifyData = identify.data as? Map<*, *> ?: error("identify data missing")
        check(identifyData["token"] == "owned-account-session-token-placeholder")
        check(identifyData["capabilities"] == 65)
        check(identifyData["compress"] == false)
        check(identifyData["largeThreshold"] == 100)
        val properties = identifyData["properties"] as? Map<*, *>
        check(properties?.get("os") == "Windows")
        check(properties?.get("browser") == "Discord Client")
        check(properties?.get("device") == "ktor")

        val nullHeartbeat = DiscordGatewayProtocol.heartbeat(null)
        check(nullHeartbeat.op == DiscordGatewayProtocol.OP_HEARTBEAT)
        check(nullHeartbeat.data == null) // JSON encoder must preserve the outer d:null field.
        val sequencedHeartbeat = DiscordGatewayProtocol.heartbeat(42L)
        check(sequencedHeartbeat.data == 42L)

        val resume = DiscordGatewayProtocol.resume(
            token = "owned-account-session-token-placeholder",
            sessionId = "session-123",
            sequence = 42L,
        )
        check(resume.op == DiscordGatewayProtocol.OP_RESUME)
        val resumeData = resume.data as? Map<*, *> ?: error("resume data missing")
        check(resumeData["session_id"] == "session-123")
        check(resumeData["seq"] == 42L)

        val activity = PresenceActivity(
            name = "Firefox",
            type = PresenceActivityType.WATCHING,
            details = "Watching a video",
            state = "YouTube",
            startedAtEpochSeconds = 1_700_000_000,
            endsAtEpochSeconds = 1_700_003_600,
            largeImage = PresenceAsset("https://cdn.example/large.webp", "Large artwork", "https://example.com/large"),
            smallImage = PresenceAsset("https://cdn.example/small.webp", "Small artwork"),
            buttons = listOf(PresenceButton("Open source", "https://example.com")),
        )
        val update = DiscordGatewayProtocol.presenceUpdate(activity, 1569772369344602173L)
        check(update.op == DiscordGatewayProtocol.OP_PRESENCE_UPDATE)
        val data = update.data as? Map<*, *> ?: error("presence data missing")
        check(data["since"] === GatewayJsonNull)
        check(data["status"] == "online")
        check(data["afk"] == false)
        val activities = data["activities"] as? List<*> ?: error("activities missing")
        val wireActivity = activities.single() as Map<*, *>
        check(wireActivity["name"] == "Firefox")
        check(wireActivity["type"] == 3)
        check(wireActivity["application_id"] == "1569772369344602173")
        val timestamps = wireActivity["timestamps"] as Map<*, *>
        check(timestamps["start"] == 1_700_000_000_000L)
        check(timestamps["end"] == 1_700_003_600_000L)
        val assets = wireActivity["assets"] as Map<*, *>
        check(assets["large_image"] == "https://cdn.example/large.webp")
        check(assets["large_text"] == "Large artwork")
        check(assets["large_url"] == "https://example.com/large")
        check(assets["small_image"] == "https://cdn.example/small.webp")
        val buttons = wireActivity["buttons"] as List<*>
        val button = buttons.single() as Map<*, *>
        check(button["label"] == "Open source")
        check(button["url"] == "https://example.com")

        check(DiscordGatewayProtocol.payloadFitsLimit("x".repeat(4_096)))
        check(!DiscordGatewayProtocol.payloadFitsLimit("x".repeat(4_097)))
        check(DiscordGatewayProtocol.payloadFitsLimit("😀".repeat(1_024))) // 4 UTF-8 bytes per code point.
        check(!DiscordGatewayProtocol.payloadFitsLimit("😀".repeat(1_025)))

        val invisibleStatus = DiscordGatewayProtocol.presenceUpdate(null, 1569772369344602173L, "invisible")
        check((invisibleStatus.data as Map<*, *>)["status"] == "invisible")
        check(GatewayCloseCodePolicy.disposition(4001) == GatewayCloseDisposition.RETRY)
        check(GatewayCloseCodePolicy.disposition(4002) == GatewayCloseDisposition.RETRY)
        check(GatewayCloseCodePolicy.disposition(4004) == GatewayCloseDisposition.AUTHENTICATION_REJECTED)
        check(GatewayCloseCodePolicy.disposition(4007) == GatewayCloseDisposition.RETRY_WITH_NEW_SESSION)
        check(GatewayCloseCodePolicy.disposition(4008) == GatewayCloseDisposition.BACKOFF)
        check(GatewayCloseCodePolicy.disposition(4015) == GatewayCloseDisposition.TOO_MANY_ACCOUNT_SESSIONS)
        check(GatewayCloseCodePolicy.disposition(4012) == GatewayCloseDisposition.TERMINAL_CONFIGURATION_ERROR)
        check(GatewayInvalidSessionPolicy.sampleRetryDelay { from, _ -> from } == 1_000L)
        check(GatewayInvalidSessionPolicy.sampleRetryDelay { _, until -> until - 1L } == 5_000L)
        check(runCatching { GatewayInvalidSessionPolicy.sampleRetryDelay { _, _ -> 0L } }.isFailure)

        val invalid = runCatching {
            DiscordGatewayProtocol.presenceUpdate(
                PresenceActivity(name = "Invalid", buttons = listOf(
                    PresenceButton("A", "https://example.com"),
                    PresenceButton("B", "https://example.com"),
                    PresenceButton("C", "https://example.com"),
                )),
                1569772369344602173L,
            )
        }
        check(invalid.isFailure)

        check(PresenceActivityType.fromWireValue(4) == PresenceActivityType.CUSTOM_STATUS)
        check(PresenceActivityType.PLAYING.displayLabel == "Playing")
        check(PresenceActivityType.STREAMING.displayLabel == "Streaming")
        check(PresenceActivityType.LISTENING.displayLabel == "Listening")
        check(PresenceActivityType.WATCHING.displayLabel == "Watching")
        check(PresenceActivityType.CUSTOM_STATUS.displayLabel == "Custom Status")
        check(PresenceActivityType.COMPETING.displayLabel == "Competing")
        check(PresenceActivityDisplayFormatter.format(PresenceActivityType.PLAYING, "Minecraft", "Survival", "Day 3").headline == "Playing Minecraft")
        check(PresenceActivityDisplayFormatter.format(PresenceActivityType.STREAMING, "Twitch", "Live coding", "Kotlin").headline == "Streaming Live coding")
        check(PresenceActivityDisplayFormatter.format(PresenceActivityType.LISTENING, "Spotify", "Track title", "Artist").headline == "Listening to Spotify")
        check(PresenceActivityDisplayFormatter.format(PresenceActivityType.WATCHING, "YouTube", "Video title", null).headline == "Watching YouTube")
        check(PresenceActivityDisplayFormatter.format(PresenceActivityType.CUSTOM_STATUS, "unused", null, "At work").headline == "At work")
        check(PresenceActivityDisplayFormatter.format(PresenceActivityType.COMPETING, "Arena", null, null).headline == "Competing in Arena")
        check(PresenceActivity(name = "Status", type = PresenceActivityType.CUSTOM_STATUS, state = "At work").validate().isEmpty())
        check(PresenceActivity(name = "Status", type = PresenceActivityType.CUSTOM_STATUS).validate().isNotEmpty())
        check(PresenceActivity(name = "Status", type = PresenceActivityType.CUSTOM_STATUS, state = "x").validate().isNotEmpty())
        check(PresenceActivity(name = "Status", details = "x").validate().isNotEmpty())
        check(PresenceActivity(name = "Status", details = "").validate().isEmpty())
        check(PresenceActivity(name = "Bad", buttons = listOf(PresenceButton("Open", "https://trusted.example@evil.example/path"))).validate().isNotEmpty())
        check(PresenceActivity(name = "Bad", largeImage = PresenceAsset("file:///sdcard/private.png")).validate().isNotEmpty())
        check(PresenceActivity(name = "Bad", largeImage = PresenceAsset("https://user:pass@cdn.example/image.png")).validate().isNotEmpty())

        val cleared = DiscordGatewayProtocol.presenceUpdate(null, 1569772369344602173L)
        check(((cleared.data as Map<*, *>)["activities"] as List<*>).isEmpty())

        val mediaUpdate = PresenceActivityMapper.fromUpdate(
            PresenceUpdate(
                source = "media:org.example.player",
                name = "Music Player",
                details = "Track title",
                state = "Artist",
                mediaState = "playing",
                startTimestampSeconds = 1_700_000_000,
                sourceType = "media-session",
                priority = 90,
            ),
        )
        check(mediaUpdate.type == PresenceActivityType.LISTENING)
        check(mediaUpdate.startedAtEpochSeconds == 1_700_000_000L)
        check(mediaUpdate.sourceId == "media:org.example.player")

        val manualRich = PresenceActivityMapper.fromUpdate(
            PresenceUpdate(
                source = "manual",
                name = "Stream",
                details = "Live coding",
                state = "Kotlin",
                mediaState = "playing",
                startTimestampSeconds = 1_700_000_000,
                activityType = PresenceActivityType.STREAMING,
                endsAtEpochSeconds = 1_700_000_600,
                largeImage = PresenceAsset("123456789012345678", "Stream cover"),
                smallImage = PresenceAsset("https://cdn.example/logo.webp", "Channel logo"),
                buttons = listOf(PresenceButton("Watch", "https://twitch.tv/example")),
                streamUrl = "https://twitch.tv/example",
            ),
        )
        check(manualRich.type == PresenceActivityType.STREAMING)
        check(manualRich.endsAtEpochSeconds == 1_700_000_600L)
        check(manualRich.largeImage?.image == "123456789012345678")
        check(manualRich.smallImage?.tooltip == "Channel logo")
        check(manualRich.buttons.single().label == "Watch")
        check(manualRich.streamUrl == "https://twitch.tv/example")

        val appUpdate = PresenceActivityMapper.fromUpdate(
            PresenceUpdate(
                source = "org.example.browser",
                name = "Browser",
                details = "Browsing",
                state = null,
                mediaState = "browsing",
                startTimestampSeconds = 0L,
                sourceType = "website",
            ),
        )
        check(appUpdate.type == PresenceActivityType.PLAYING)
        check(appUpdate.startedAtEpochSeconds == null)
        println("DiscordGatewayProtocolSelfTest: identify, heartbeat, resume, source mapping, full activity mapping, and clear payload passed")
    }
}
