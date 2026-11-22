#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
sh "$ROOT/scripts/test-core.sh"
bash "$ROOT/scripts/security-audit.sh"

python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
app = root / "app"
android = "{http://schemas.android.com/apk/res/android}"
main_manifest_path = app / "src/main/AndroidManifest.xml"
automation_manifest_path = app / "src/automation/AndroidManifest.xml"
main = ET.parse(main_manifest_path).getroot()
automation = ET.parse(automation_manifest_path).getroot()
application = main.find("application")
if application is None:
    raise SystemExit("Main manifest has no application")

checks = []
def require(condition, message):
    if not condition:
        raise SystemExit("FAIL: " + message)
    checks.append(message)

# Android manifest and least privilege.
permissions = {n.attrib.get(android + "name", "") for n in main.findall("./uses-permission")}
require("android.permission.INTERNET" in permissions, "Gateway has INTERNET permission")
require("android.permission.PACKAGE_USAGE_STATS" not in permissions, "secure flavor has no Usage Access declaration")
require(application.attrib.get(android + "usesCleartextTraffic") == "false", "cleartext traffic disabled")
require(application.attrib.get(android + "allowBackup") == "false", "app backup disabled")
services = application.findall("service")
require(all(s.attrib.get(android + "exported") == "false" for s in services), "main services are not exported")
exported_activities = [a for a in application.findall("activity") if a.attrib.get(android + "exported") == "true"]
require(len(exported_activities) == 1 and exported_activities[0].attrib.get(android + "name") == ".MainActivity", "only launcher Activity is exported")
automation_permissions = {n.attrib.get(android + "name", "") for n in automation.findall("./uses-permission")}
require("android.permission.PACKAGE_USAGE_STATS" in automation_permissions, "automation flavor declares Usage Access")
automation_services = {s.attrib.get(android + "name", "").split(".")[-1] for s in automation.findall("./application/service")}
require({"PresenceNotificationListener", "BrowserAccessibilityService"}.issubset(automation_services), "automation flavor declares media and browser services")
xml_files = list((app / "src/main").rglob("*.xml")) + list((app / "src/automation").rglob("*.xml"))
for path in xml_files:
    ET.parse(path)

# Native SDK removal and Gateway dependency contract.
build = (app / "build.gradle.kts").read_text()
require("io.ktor:ktor-client-core:2.3.13" in build, "Ktor client-core pinned to 2.3.13")
require("io.ktor:ktor-client-cio:2.3.13" in build, "Ktor CIO engine pinned to 2.3.13")
require("io.ktor:ktor-client-websockets:2.3.13" in build, "Ktor WebSockets pinned to 2.3.13")
require("DISCORD_SDK_ENABLED" not in build and "discord_partner_sdk.aar" not in build, "Social SDK AAR dependency removed")
require("externalNativeBuild" not in build and "CMakeLists.txt" not in build and "prefab" not in build, "native build toolchain removed")
require(not (app / "src/main/java/app/geckopresence/host/DiscordSdk.kt").exists(), "legacy DiscordSdk adapter removed")
require(not (app / "src/main/cpp").exists(), "JNI/C++ sources removed")
require("native <methods>" not in (app / "proguard-rules.pro").read_text(), "JNI keep rules removed")

# Transport and wire-model invariants.
publisher_path = app / "src/main/java/app/geckopresence/host/DiscordGatewayPublisher.kt"
protocol_path = app / "src/main/java/app/geckopresence/host/core/DiscordGatewayProtocol.kt"
activity_path = app / "src/main/java/app/geckopresence/host/core/PresenceActivity.kt"
mapper_path = app / "src/main/java/app/geckopresence/host/core/PresenceActivityMapper.kt"
publisher = publisher_path.read_text()
protocol = protocol_path.read_text()
activity_model = activity_path.read_text()
mapper = mapper_path.read_text()
require("HttpClient(CIO)" in publisher and "install(WebSockets)" in publisher, "publisher uses Ktor CIO WebSocket client")
require("webSocketSession(urlString = targetUrl)" in publisher, "publisher owns WebSocket session lifecycle")
require("startHeartbeat" in publisher and "awaitingHeartbeatAck" in publisher, "heartbeat jitter and ACK tracking implemented")
require('socket?.close(CloseReason(GATEWAY_RECONNECT_CLOSE_CODE, "Gateway connection interrupted"))' in publisher, "failed Gateway handshake closes the half-open socket before retry")
require("RESUMED" in publisher and "resumeGatewayUrl" in publisher and "lastSequence" in publisher, "resume state is retained for reconnect")
require('uri.scheme.equals("wss", ignoreCase = true)' in publisher and 'host.endsWith(".discord.gg")' in publisher, "resume URL is restricted to Discord WSS hosts")
require("MAX_PAYLOAD_BYTES" in protocol and "4_096" in protocol and "payloadFitsLimit(payload)" in publisher and "StandardCharsets.UTF_8" in protocol, "outbound payload size is bounded by UTF-8 bytes")
require("MIN_PRESENCE_INTERVAL_MS = 4_100L" in protocol and "Channel.CONFLATED" in publisher, "presence updates are coalesced and rate-limited")
require("candidate.validate()" in protocol, "presence data is validated before serialization")
require("PresenceActivityDisplayFormatter" in activity_model and "PresenceActivityType.STREAMING" in activity_model, "pure preview formatter follows type-specific Discord Activity formats")
for field in ("large_image", "small_image", "large_text", "small_text", "large_url", "small_url", "buttons", "timestamps", "streamUrl"):
    require(field in protocol or field in activity_model, f"Rich Presence field support: {field}")
require('"session_id" to sessionId' in protocol and '"seq" to sequence' in protocol, "Resume payload contains session id and sequence")
require('"activities" to activities' in protocol and '"status" to status' in protocol, "Presence Update payload preserves selected account status")
require("PresenceActivityMapper::fromUpdate" in (app / "src/main/java/app/geckopresence/host/PresenceEngineService.kt").read_text(), "winning source is mapped to transport-neutral activity")
require('PresenceActivityType.LISTENING' in mapper and 'PresenceActivityType.PLAYING' in mapper, "media and non-media source type mapping exists")
require("Log." not in publisher and "println(" not in publisher and "exception.message" not in publisher, "Gateway transport does not log frame/token/exception contents")
close_policy = (app / "src/main/java/app/geckopresence/host/core/GatewayCloseCodePolicy.kt").read_text()
require("GatewayCloseCodePolicy.disposition(receivedCloseCode)" in publisher, "close-code recovery is centralized and testable")
require("4004 -> GatewayCloseDisposition.AUTHENTICATION_REJECTED" in close_policy, "only documented authentication failure 4004 forces re-login")
require("4001, 4002" not in publisher and "4001, 4002" not in close_policy, "recoverable opcode/decode close codes are not misclassified as terminal")
require("4008 -> GatewayCloseDisposition.BACKOFF" in close_policy, "Gateway rate-limit close code applies bounded backoff")
invalid_session = (app / "src/main/java/app/geckopresence/host/core/GatewayInvalidSessionPolicy.kt").read_text()
require("GatewayInvalidSessionPolicy.sampleRetryDelay" in publisher and "MIN_RETRY_DELAY_MS" in invalid_session and "MAX_RETRY_DELAY_MS" in invalid_session, "INVALID_SESSION always observes a bounded randomized retry window")
require("pendingStatus" in publisher and "ALLOWED_PRESENCE_STATUSES" in publisher, "account status is validated and coalesced with activity updates")

# Vault and explicit user-controlled connection.
vault = (app / "src/main/java/app/geckopresence/host/DiscordCredentialVault.kt").read_text()
service = (app / "src/main/java/app/geckopresence/host/PresenceEngineService.kt").read_text()
main_activity = (app / "src/main/java/app/geckopresence/host/MainActivity.kt").read_text()
require("AndroidKeyStore" in vault and "KeyGenParameterSpec" in vault, "credential key uses Android Keystore")
require("AES/GCM/NoPadding" in (app / "src/main/java/app/geckopresence/host/core/DiscordTokenSecurity.kt").read_text(), "credential envelope uses AES-GCM")
require("loadForConnection(this)" in service and "gatewayPublisher?.connect(token)" in service, "credential is passed from local vault to Gateway publisher")
require("RpcCommand.CONNECT -> connectDiscordGateway()" in service and "ACTION_TURN_ON" in service and "stopService(Intent(this, PresenceEngineService::class.java))" in main_activity, "one Home Turn On / Turn Off action controls engine and Gateway lifecycle")
service_start = re.search(r"override fun onCreate\(\).*?\n    }", service, re.S)
require(service_start is not None and "gatewayPublisher?.connect(" not in service_start.group(0), "engine startup does not auto-connect with stored session")
require("gatewayPublisher?.publish(winner?.let(PresenceActivityMapper::fromUpdate), selectedStatus)" in service, "arbitrated activity and selected account status reach Gateway publisher")
require("gatewayPublisher?.shutdown()" in service, "publisher closes on service destruction")
require("onSessionRejected =" in service and "DiscordCredentialVault.clear(this@PresenceEngineService)" in service, "rejected Gateway credentials are removed from the local vault")
require("KEY_MANUAL_ACTIVITY_TYPE" in service and "KEY_MANUAL_LARGE_IMAGE" in service and "KEY_MANUAL_BUTTON1_URL" in service, "manual Studio fields are restored into source model")
require("endsAtEpochSeconds = update.endsAtEpochSeconds" in mapper and "largeImage = update.largeImage" in mapper and "buttons = update.buttons" in mapper, "manual source mapper preserves advanced Rich Presence fields")
require("largeImage = assetFrom(largeImage, largeTooltip)" in (app / "src/main/java/app/geckopresence/host/MainActivity.kt").read_text(), "Studio collects large artwork fields")
main_activity = (app / "src/main/java/app/geckopresence/host/MainActivity.kt").read_text()
require('textField("Start timestamp · Unix seconds (optional, defaults to now)", "studio_draft_start_timestamp"' in main_activity, "Studio exposes an optional start timestamp")
require('putLong(PresenceEngineService.KEY_MANUAL_START, resolvedStart)' in main_activity, "custom start timestamp persists into the running source")
require('"studio_draft_start_timestamp"' in main_activity, "custom start timestamp is included in saved preset fields")
require('details = app.geckopresence.host.core.PresenceRules.cleanText(settings.getString(KEY_MANUAL_DETAILS, null), 120).orEmpty()' in service, "manual Studio details may be intentionally omitted")
require("PresenceActivityType.entries" in (app / "src/main/java/app/geckopresence/host/MainActivity.kt").read_text(), "Studio exposes all six activity types")
require("advancedFieldsJson" in (app / "src/main/java/app/geckopresence/host/PresencePresetStore.kt").read_text(), "local preset library stores advanced Studio configuration")
require("previewType.text = selectedType.displayLabel.uppercase()" in (app / "src/main/java/app/geckopresence/host/MainActivity.kt").read_text(), "Studio preview follows the selected activity type")
main_activity = (app / "src/main/java/app/geckopresence/host/MainActivity.kt").read_text()
require("PresenceActivityDisplayFormatter.format(" in main_activity and "previewName.text = preview.headline" in main_activity, "Studio preview uses Discord type-specific Gateway headline formats")
require("FrameLayout(this)" in main_activity and "Gravity.BOTTOM or Gravity.END" in main_activity and "previewArtworkStack" in main_activity, "Studio preview uses Kizzy-style large artwork with a small-image overlay")
require("scheduleStudioSync =" in main_activity and "STUDIO_SYNC_DEBOUNCE_MS" in main_activity and "applyStudio(false)" in main_activity, "active Studio edits debounce and auto-publish through shared validation")
require("ACTION_TURN_ON" in main_activity and "RuntimeTogglePolicy.isOn" in main_activity and "CONNECT DISCORD GATEWAY" not in main_activity, "single Home runtime toggle replaces separate connect controls")
require("previewDetails.text = preview.details ?: \"No additional details\"" in main_activity, "Studio preview keeps optional Details separate from the type-specific headline")
require("Discord formats the headline by activity type" in main_activity, "Studio explains type-specific Discord Activity display semantics")
require('textField("Activity name", "studio_draft_name"' in main_activity, "Studio field label matches Discord Activity.name semantics")
require("activity name controls the first line" not in main_activity.lower() and "Activity name · first line" not in main_activity, "Studio no longer claims Activity name always controls the first line")
require("detailsLine(" not in mapper_path.read_text(), "legacy label/details concatenation helper is removed from runtime mapper")
require("`name` remains the activity name" in (root / "docs/DISCORD_GATEWAY.md").read_text() and "type-specific headline formats" in (root / "docs/DISCORD_GATEWAY.md").read_text(), "Gateway documentation describes Activity.name and type-specific display semantics")
require('previewName.text = "Discord application name"' not in main_activity, "Studio no longer incorrectly claims the application name is immutable")
require("sendEnvelope(DiscordGatewayProtocol.presenceUpdate(null" not in publisher, "disconnect does not forcibly overwrite account status with online")
require("EXTRA_GATEWAY_STATE" in service and "KEY_GATEWAY_STATE" in service, "runtime exposes Gateway status only")

# Session import is explicit and local-only; browser storage is never inspected.
login_path = app / "src/main/java/app/geckopresence/host/DiscordLoginActivity.kt"
login = login_path.read_text()
require("DiscordSessionImportDecoder.decodeJavascriptResult" in login and "DiscordCredentialVault.save(this, token)" in login, "imported session is decoded and stored only through the local vault")
require("FLAG_SECURE" in login and "addJavascriptInterface" not in login, "login screen blocks screenshots and exposes no JavaScript bridge")

# CI must exercise both product flavors.
workflow_path = root / ".github/workflows/gecko.yml"
require(workflow_path.exists(), "Android CI workflow exists")
workflow = workflow_path.read_text()
for task in (":app:testSecureDebugUnitTest", ":app:testAutomationDebugUnitTest", ":app:assembleSecureDebug", ":app:assembleAutomationDebug"):
    require(task in workflow, f"CI verifies {task}")
require("gradle-version: '8.10.2'" in workflow, "CI Gradle toolchain matches project wrapper")
require("kotlin-compiler-2.0.21.zip" in workflow and "sha256sum -c -" in workflow, "CI installs checksum-pinned Kotlin CLI for standalone test harnesses")

# Advanced per-app profiles and local-first artwork gallery.
profile_store_path = app / "src/main/java/app/geckopresence/host/AppPresenceProfileStore.kt"
gallery_path = app / "src/main/java/app/geckopresence/host/ArtworkGalleryStore.kt"
profile_store = profile_store_path.read_text()
gallery = gallery_path.read_text()
require("REQUEST_ARTWORK_PICKER" in main_activity and "Intent.ACTION_OPEN_DOCUMENT" in main_activity, "Studio uses Android document picker for local artwork import")
require("KEY_PENDING_ARTWORK_TARGET" in main_activity and "KEY_PENDING_ARTWORK_ID" in main_activity and "pendingArtworkField" not in main_activity, "artwork destination survives Activity recreation without transient view references")
require("loadArtworkPreview" in main_activity and "previewLargeArtwork" in main_activity and "previewSmallArtwork" in main_activity, "Studio renders Large and Small artwork previews")
require("USE LARGE" in main_activity and "localReference(item.id)" in main_activity and "COPY IMAGE ID" not in main_activity and "COPY PRESET ID" in main_activity, "Gallery links artwork by stable reference with no manual ID copy")
require("ArtworkGalleryStore.previewFile(this, artwork)" in main_activity and "fun previewFile(context: Context, artwork: Artwork)" in (app / "src/main/java/app/geckopresence/host/ArtworkGalleryStore.kt").read_text(), "preview decoding uses a path/size-checked lightweight file accessor while upload retains SHA-256 verification")
require("PresencePresetStore.duplicate(this, preset.id)" in main_activity and "fun duplicate(context: Context, id: String)" in (app / "src/main/java/app/geckopresence/host/PresencePresetStore.kt").read_text(), "Library supports duplicating a saved preset")
require("Executors.newSingleThreadExecutor" in main_activity and "artworkPreviewExecutor?.shutdownNow()" in main_activity, "artwork thumbnails decode on one bounded background worker and stop with the Activity")
require("linkImportedArtwork" in main_activity and "confirmArtworkUpload" not in main_activity and "Upload artwork publicly?" not in main_activity, "picked artwork is linked and hosted automatically without a confirmation or paste step")
auto_artwork = (app / "src/main/java/app/geckopresence/host/ArtworkAutoPublisher.kt").read_text()
media_tracker = (app / "src/main/java/app/geckopresence/host/MediaSessionPresenceTracker.kt").read_text()
require("KEY_AUTO_ARTWORK" in auto_artwork and "getBoolean(KEY_AUTO_ARTWORK, true)" in auto_artwork and "isConfigured(context)" in auto_artwork, "automatic artwork is on by default and only active once a webhook is saved")
require("addOnActiveSessionsChangedListener" in media_tracker and "OnActiveSessionsChangedListener" in media_tracker, "media detector subscribes to active-session changes")
require("registerCallback(controllerCallback, handler)" in media_tracker and "onMetadataChanged" in media_tracker and "onPlaybackStateChanged" in media_tracker, "media detector listens for metadata and playback changes")
require("MISSING_SESSION_GRACE_MS" in media_tracker and "POLL_INTERVAL_MS = 10_000L" in media_tracker, "media detector tolerates transient empty session lists and keeps a polling fallback")
require("INITIAL_ARTWORK_RETRY_DELAY_MS" in media_tracker and "MAX_ARTWORK_RETRY_ATTEMPTS" in media_tracker and "nextArtworkAttemptAtEpochMillis" in media_tracker, "media artwork upload retries transient failures with bounded backoff")
require("METADATA_KEY_ALBUM_ART_URI" in auto_artwork and "METADATA_KEY_ART_URI" in auto_artwork and "METADATA_KEY_ALBUM_ART" in auto_artwork and "METADATA_KEY_ART" in auto_artwork and "getApplicationIcon" in auto_artwork, "automatic artwork resolver uses media URI/bitmap metadata with app-icon fallback")
require('uri.scheme.equals("content", ignoreCase = true)' in auto_artwork and 'uri.scheme.equals("https", ignoreCase = true)' not in auto_artwork, "metadata artwork resolver avoids fetching arbitrary network URLs")
require("ArtworkMediaPolicy.sha256(bytes)" in auto_artwork and "DiscordArtworkPolicy.isFresh" in auto_artwork and "MAX_CACHE_ENTRIES = 200" in auto_artwork and "DiscordArtworkUploader.uploadAndReference" in auto_artwork, "automatic artwork is hash-deduplicated, TTL-cached, bounded and uses the Discord-native uploader")
require("cacheDir" not in auto_artwork and "deleteWebhookMessage" in auto_artwork, "artwork is processed in memory and obsolete webhook messages are cleaned up")
require("val activityClassName = event.className" in (app / "src/main/java/app/geckopresence/host/UsageAppTracker.kt").read_text() and "eventClassName = activityClassName" in (app / "src/main/java/app/geckopresence/host/UsageAppTracker.kt").read_text(), "app detection tracks Activity class to ignore stale pause events")
require("KEY_MANUAL_ACTIVE" in main_activity and "STUDIO_SYNC_DEBOUNCE_MS" in main_activity and "applyStudio(false)" in main_activity, "active Studio overrides debounce valid changes to the Gateway publisher")
require("DISCORD ARTWORK" in main_activity and "SAVE WEBHOOK" in main_activity and "SEND TEST IMAGE" in main_activity and "ArtworkUploadCredentialVault" in main_activity, "users can configure and test their webhook from Settings")
require("activityType = profile.activityType" in (app / "src/main/java/app/geckopresence/host/AppPresencePolicy.kt").read_text(), "per-app activity type reaches source arbitration")
require("accountStatus = profile.accountStatus" in (app / "src/main/java/app/geckopresence/host/AppPresencePolicy.kt").read_text(), "per-app account status reaches the winning presence update")
require("schemaVersion" in profile_store and "buttons" in profile_store and "accountStatus" in profile_store, "advanced per-app profile schema persists versioned activity fields")
require("ArtworkMediaPolicy.sha256(bytes)" in gallery and "target.length() != artwork.byteCount" in gallery, "gallery verifies content integrity before external upload")
require("instanceFollowRedirects = false" in (app / "src/main/java/app/geckopresence/host/DiscordArtworkUploader.kt").read_text() and "discord.com" in (app / "src/main/java/app/geckopresence/host/DiscordArtworkUploader.kt").read_text(), "uploader pins discord.com and never follows redirects")
require("gecko-cloudflare-artwork-worker" not in workflow and "imgur" not in workflow.lower(), "CI has no third-party artwork hosting steps")

print(f"Source contract checks passed: {len(checks)} assertions, {len(xml_files)} XML files, Ktor Gateway transport, no Social SDK/JNI, secure credential boundary, secure/automation flavors, dual-flavor CI")
PY
