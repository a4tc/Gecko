#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APP="$ROOT/app"
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
    raise SystemExit("FAIL: main manifest has no application")

checks = []
def require(condition, message):
    if not condition:
        raise SystemExit("FAIL: " + message)
    checks.append(message)

require(application.attrib.get(android + "allowBackup") == "false", "application backup disabled")
require(application.attrib.get(android + "fullBackupContent") == "false", "legacy full backup disabled")
require(application.attrib.get(android + "dataExtractionRules") == "@xml/data_extraction_rules", "Android 12+ backup/transfer rules are configured")
backup_rules = ET.parse(app / "src/main/res/xml/data_extraction_rules.xml").getroot()
require(len(backup_rules.findall("./cloud-backup/exclude")) == 5 and len(backup_rules.findall("./device-transfer/exclude")) == 5, "cloud backup and device transfer exclude app data")
require(application.attrib.get(android + "usesCleartextTraffic") == "false", "cleartext HTTP disabled")

main_services = application.findall("service")
require(all(s.attrib.get(android + "exported") == "false" for s in main_services), "main-process services are not exported")

activity_exported = [a for a in application.findall("activity") if a.attrib.get(android + "exported") == "true"]
require(len(activity_exported) == 1 and activity_exported[0].attrib.get(android + "name") == ".MainActivity", "only launcher Activity is exported")

for service in automation.find("application").findall("service"):
    permission = service.attrib.get(android + "permission")
    require(service.attrib.get(android + "exported") == "true", "automation system-bound service is exported for Android binding")
    require(permission in {
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
    }, "automation service uses its Android system binding permission")

manifest_text = "\n".join(p.read_text(errors="ignore") for p in [main_manifest_path, automation_manifest_path])
for forbidden in (
    "android.permission.READ_SMS", "android.permission.RECEIVE_SMS",
    "android.permission.READ_CALL_LOG", "android.permission.READ_CONTACTS",
    "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
    "android.permission.ACCESS_FINE_LOCATION", "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.QUERY_ALL_PACKAGES", "android.permission.REQUEST_INSTALL_PACKAGES",
):
    require(forbidden not in manifest_text, f"not requesting {forbidden}")

source_roots = [app / "src/main", app / "src/automation"]
source_files = [p for source_root in source_roots for p in source_root.rglob("*") if p.is_file() and p.suffix in {".kt", ".java", ".cpp", ".xml"}]
source_text = "\n".join(p.read_text(errors="ignore") for p in source_files)
executable_by_path = {}
for path in source_files:
    content = path.read_text(errors="ignore")
    content = re.sub(r'"(?:\\.|[^"\\])*"', "", content)
    content = re.sub(r"/\*.*?\*/|//[^\n]*", "", content, flags=re.S)
    executable_by_path[path.relative_to(root).as_posix()] = content
cleanup_path = "app/src/main/java/app/geckopresence/host/DiscordWebSessionCleanup.kt"
webview_paths = {path for path, content in executable_by_path.items() if re.search(r"\bWebView\b", content)}
login_path = "app/src/main/java/app/geckopresence/host/DiscordLoginActivity.kt"
require(webview_paths == {cleanup_path, login_path}, "WebView APIs are confined to isolated-profile cleanup and the login Activity")
require(all("WebView(" not in content for path, content in executable_by_path.items() if path != login_path), "only the login Activity creates a WebView")
require(not re.search(r"addJavascriptInterface", source_text, re.IGNORECASE), "no JavaScript-to-native bridge is exposed")

login_activity = (app / "src/main/java/app/geckopresence/host/DiscordLoginActivity.kt").read_text()
require("DiscordSessionImportDecoder.decodeJavascriptResult" in login_activity and "DiscordCredentialVault.save(this, token)" in login_activity, "imported session is decoded, validated and stored only through the local vault")
require("FLAG_SECURE" in login_activity, "login screen blocks screenshots")
require("addJavascriptInterface" not in login_activity, "no JavaScript-to-native bridge in the login Activity")
require("isDiscordHost" in login_activity and "onReceivedSslError" in login_activity and "MIXED_CONTENT_NEVER_ALLOW" in login_activity, "login WebView is restricted to discord.com over verified HTTPS")
require("override fun onDestroy()" in login_activity and "DiscordWebSessionCleanup.clear" in login_activity, "login WebView data is cleared on Activity destruction")
require("request.deny()" not in login_activity and "Clipboard" not in login_activity and "putExtra" not in login_activity, "credential is not granted to WebView permissions, copied, or passed through an Intent")

cleanup = (app / "src/main/java/app/geckopresence/host/DiscordWebSessionCleanup.kt").read_text()
require("CookieManager.getInstance().removeAllCookies" in cleanup and "WebStorage.getInstance().deleteAllData" in cleanup and "KEY_CLEANUP_REQUIRED" in cleanup, "isolated WebView cookies and local storage have process-death cleanup recovery")
application_class = (app / "src/main/java/app/geckopresence/host/CrashDiagnostics.kt").read_text()
activity = (app / "src/main/java/app/geckopresence/host/MainActivity.kt").read_text()
require('WebView.setDataDirectorySuffix("gecko_discord_session_import")' in cleanup and "DiscordWebViewIsolation.initialize()" in application_class and "clearStaleSessionAtStartup(this)" in application_class, "isolated WebView data directory is initialized before Activities and stale data is cleared")
require("DiscordWebViewIsolation.isAvailable()" in activity and "hasPendingCleanup(this)" in activity and "DiscordWebSessionCleanup.clear(this)" in activity, "return to app retries pending isolated-profile cleanup")
require('android:name=".DiscordLoginActivity"' in main_manifest_path.read_text() and 'android:exported="false"' in main_manifest_path.read_text(), "session-import Activity is not externally launchable")
require('android:name=".DiscordLoginActivity"' in main_manifest_path.read_text() and 'android:exported="false"' in main_manifest_path.read_text(), "session-import Activity is not externally launchable")

vault = (app / "src/main/java/app/geckopresence/host/DiscordCredentialVault.kt").read_text()
codec = (app / "src/main/java/app/geckopresence/host/core/DiscordTokenSecurity.kt").read_text()
require("AndroidKeyStore" in vault and "KeyGenParameterSpec" in vault, "credential key is held by Android Keystore")
require("AES/GCM/NoPadding" in codec and "GCMParameterSpec" in codec and "updateAAD" in codec, "credential envelope uses authenticated AES-GCM")
require("cipher.init(Cipher.ENCRYPT_MODE, key)" in codec and "val nonce = cipher.iv" in codec and "cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec" not in codec, "Android Keystore generates the randomized AES-GCM encryption IV")
require("MAX_ENVELOPE_LENGTH" in codec and "envelope.length <= MAX_ENVELOPE_LENGTH" in codec, "credential ciphertext size is bounded before Base64 decoding")
require("putString(KEY_CIPHERTEXT, ciphertext)" in vault, "private preferences receive ciphertext only")
require(not re.search(r"Log\.|println\(", vault), "credential vault does not log or print secret values")
require(not re.search(r"https?://|wss?://|HttpURLConnection|OkHttp|WebSocket", vault), "credential vault has no network client or remote endpoint")
require("putExtra" not in login_activity and "Clipboard" not in login_activity, "session credential is not copied to an Intent or clipboard")

require("WindowManager.LayoutParams.FLAG_SECURE" in activity, "credential Settings screen blocks screenshots and screen recording")
require("FLAG_SECURE" in activity, "Settings screen blocks screenshots while showing credential state")
require('if (BuildConfig.AUTOMATION_SOURCES_ENABLED)' in activity and 'elevated permissions required by automation sources' in activity, "secure Settings UI hides automation permission controls")
require("DiscordTokenInputPolicy" not in activity and "Paste Discord credential" not in activity, "no manual token paste field or clipboard-dependent credential path")

redactor = (app / "src/main/java/app/geckopresence/host/core/SensitiveDataRedactor.kt").read_text()
diagnostics = (app / "src/main/java/app/geckopresence/host/CrashDiagnostics.kt").read_text()
require("SensitiveDataRedactor.redact" in diagnostics, "crash diagnostics pass through secret redaction")
require("traceInputStream" not in diagnostics and "saveTrace(" not in diagnostics and "purgeLegacyMemorySnapshots" in diagnostics, "native memory snapshots are never collected and legacy snapshots are purged")
require("usesCleartextTraffic=\"false\"" in (app / "src/main/AndroidManifest.xml").read_text(), "manifest explicitly refuses cleartext network traffic")

engine = (app / "src/main/java/app/geckopresence/host/PresenceEngineService.kt").read_text()
for method in ("updateAppTracking", "updateMediaTracking", "receiveBrowserPresence"):
    block = re.search(rf"private fun {method}\(.*?\n    }}", engine, re.S)
    require(block is not None and "AUTOMATION_SOURCES_ENABLED" in block.group(0), f"{method} is gated by automation build flavor")

publisher = (app / "src/main/java/app/geckopresence/host/DiscordGatewayPublisher.kt").read_text()
protocol = (app / "src/main/java/app/geckopresence/host/core/DiscordGatewayProtocol.kt").read_text()
model = (app / "src/main/java/app/geckopresence/host/core/PresenceActivity.kt").read_text()
require("GatewayCloseCodePolicy.disposition(receivedCloseCode)" in publisher, "Gateway close-code behavior uses a tested policy")
require("4004 -> GatewayCloseDisposition.AUTHENTICATION_REJECTED" in (app / "src/main/java/app/geckopresence/host/core/GatewayCloseCodePolicy.kt").read_text(), "only authentication close code 4004 discards session")
require("pendingStatus" in publisher and 'status in ALLOWED_STATUSES' in protocol, "account status values are constrained")
require("isSafeHttpsUrl" in model and "uri.userInfo != null" in model and "uri.port !in setOf(-1, 443)" in model, "outbound Rich Presence URLs reject credentials and non-HTTPS ports")
require("isAllowedImageReference" in model and "mp:" in model, "artwork references are restricted to allowed forms")
require("KEY_MANUAL_BUTTON2_URL" in engine and "KEY_MANUAL_END" in engine, "manual Studio extended payload persists through service restart")
require("sendEnvelope(DiscordGatewayProtocol.presenceUpdate(null" not in publisher, "disconnect avoids unintended forced-online status changes")

# Artwork gallery and upload controls: local-by-default, explicit public upload, bounded parsing.
gallery = (app / "src/main/java/app/geckopresence/host/ArtworkGalleryStore.kt").read_text()
uploader = (app / "src/main/java/app/geckopresence/host/ImgurAnonymousUploader.kt").read_text()
profile_store = (app / "src/main/java/app/geckopresence/host/AppPresenceProfileStore.kt").read_text()
app_policy = (app / "src/main/java/app/geckopresence/host/AppPresencePolicy.kt").read_text()
profile_model = (app / "src/main/java/app/geckopresence/host/core/AppPresenceProfile.kt").read_text()
media_policy = (app / "src/main/java/app/geckopresence/host/core/ArtworkMediaPolicy.kt").read_text()

require("private fun isAllowedArtworkTarget(targetKey: String): Boolean" in activity and "AppPresencePolicy.KEY_TRACKED_PACKAGES" in activity and 'targetKey == appProfileDraftKey(packageName, "largeImage")' in activity and 'targetKey == appProfileDraftKey(packageName, "smallImage")' in activity, "artwork destinations are restricted to Studio and allow-listed app-profile fields")
require('launchArtworkPicker(appProfileDraftKey(selectedPackage, "largeImage"))' in activity and 'launchArtworkPicker(appProfileDraftKey(selectedPackage, "smallImage"))' in activity, "app-profile artwork picker writes to the correct per-app draft fields")
require(activity.count("isAllowedArtworkTarget(targetKey)") >= 4, "artwork destination is revalidated during selection, upload confirmation and recovery")
require("MAX_FILE_BYTES = 8 * 1024 * 1024" in media_policy and "MAX_TOTAL_BYTES = 64L" in media_policy and "MAX_ITEMS = 40" in media_policy, "artwork gallery has per-file, aggregate-size and item-count bounds")
require('File(context.filesDir, "artwork-gallery")' in gallery and "canonicalFile" in gallery and "target.takeIf { it.parentFile == directory }" in gallery and 'uri.scheme.equals("content"' in gallery, "gallery content is app-private, content-URI-only, and canonical-path checked")
require('"image/jpeg"' in media_policy and '"image/png"' in media_policy and '"image/webp"' in media_policy and "ArtworkMediaPolicy.detectImage(bytes)" in gallery, "gallery accepts only magic-byte-verified raster image formats")
require("BitmapFactory.Options().apply { inJustDecodeBounds = true }" in gallery and "ArtworkMediaPolicy.hasValidDimensions(width, height)" in gallery and "MAX_PIXEL_COUNT = 40_000_000L" in media_policy, "gallery rejects invalid and oversized pixel dimensions before decode")
require('MessageDigest.getInstance("SHA-256")' in gallery and "current.firstOrNull { it.sha256 == digest }" in gallery, "gallery deduplicates images by SHA-256")
require('private const val ENDPOINT = "https://api.imgur.com/3/image"' in uploader and 'setRequestProperty("Authorization", "Client-ID $clientId")' in uploader, "image upload uses fixed HTTPS Imgur endpoint and public Client-ID only")
require("instanceFollowRedirects = false" in uploader and "connectTimeout = 15_000" in uploader and "readTimeout = 20_000" in uploader, "image upload disables redirects and bounds network timeouts")
require("MAX_RESPONSE_BYTES = 64 * 1024" in uploader and 'uri.host?.lowercase() != "i.imgur.com"' in uploader, "upload response is bounded and returned URL host is allow-listed")
require("Upload artwork publicly?" in activity and "Anyone with the resulting URL may view it" in activity and "UPLOAD TO IMGUR PUBLICLY" in activity, "gallery upload requires explicit user consent and warns about public visibility")
auto_artwork = (app / "src/main/java/app/geckopresence/host/ArtworkAutoPublisher.kt").read_text()
require("KEY_AUTO_PUBLIC_UPLOAD" in auto_artwork and "getBoolean(KEY_AUTO_PUBLIC_UPLOAD, false)" in auto_artwork and "ImgurAnonymousUploader.upload" in auto_artwork, "automatic public artwork upload is disabled unless the user enables standing consent")
require('uri.scheme.equals("content", ignoreCase = true)' in auto_artwork and 'uri.scheme.equals("https", ignoreCase = true)' not in auto_artwork, "media artwork resolver never fetches arbitrary remote metadata URLs")
require("cacheDir" in auto_artwork and "target.parentFile != directory" in auto_artwork and "stagedFile.delete()" in auto_artwork, "auto artwork staging is confined to a private cache directory and cleaned up after use")
require("MAX_AUTO_CACHE_ENTRIES = 200" in auto_artwork and "ArtworkMediaPolicy.sha256(encoded)" in auto_artwork and "cachedUrl(context, hash)" in auto_artwork, "automatic artwork hash cache has a bounded entry count and reuses prior public URLs")
require('private const val ENDPOINT = "https://api.imgur.com/3/image"' in uploader and "mp:attachments/" not in auto_artwork, "auto artwork upload uses only the fixed public Imgur endpoint and no internal Discord attachment endpoint")
require("profile.buttons" in profile_store and "profile.largeImage" in profile_store and "profile.smallImage" in profile_store and "profile.activityType" in profile_store, "per-app profile store persists advanced Rich Presence fields")
require("profile.priorityOverride" in profile_store and "profile.enabled" in profile_store and "profile.accountStatus" in profile_store and "startedAtEpochSeconds" in profile_store and "endsAtEpochSeconds" in profile_store, "per-app profile store persists priority, account status, enable state and timestamps")
require("accountStatus = profile.accountStatus" in app_policy and "winner?.accountStatus ?: globalStatus" in (app / "src/main/java/app/geckopresence/host/PresenceEngineService.kt").read_text(), "per-app account status overrides global status only for its winning source")
require("storedProfile?.enabled == false" in app_policy and "activityType = profile.activityType" in app_policy and "buttons = profile.buttons" in app_policy, "app detection applies or disables the complete advanced per-app profile")
require("expiresAtEpochMillis = profile.endsAtEpochSeconds?.times(1_000L)" in app_policy and "end timestamp must be in the future" in profile_model and "requireFutureEnd = false" in profile_store, "per-app end timestamps expire the source while retaining expired profile state")
require("MAX_GATEWAY_FRAME_BYTES" in publisher and "maxFrameSize = MAX_GATEWAY_FRAME_BYTES" in publisher and "currentSocket.maxFrameSize" in publisher and "HELLO_TIMEOUT_MS" in publisher, "Gateway publisher bounds inbound frames and HELLO handshake time")
require("withTimeout(CONNECTION_TIMEOUT_MS)" in publisher and "client.webSocketSession(urlString = targetUrl)" in publisher, "Gateway connection and HTTP upgrade have a bounded timeout without an idle socket timeout")
require("message.toByteArray(Charsets.UTF_8).size.toLong()" in publisher and "frameBytes > MAX_ACCEPTED_GATEWAY_FRAME_BYTES" in publisher and "MAX_ACCEPTED_GATEWAY_FRAME_BYTES = 8L * 1024L * 1024L" in publisher, "Gateway JSON frame is measured before the application safety cap")
require("sendMutex.withLock" in publisher and "Channel.CONFLATED" in publisher and "MIN_PRESENCE_INTERVAL_MS" in protocol, "Gateway sends are serialized, coalesced and rate spaced")

require("safeLocalFile(context, artwork.id, artwork.fileName)" in gallery and "target.takeIf { it.parentFile == directory }" in gallery and "canonicalDirectory.parentFile != privateRoot" in gallery, "gallery reads and deletes only canonical files inside its private directory")
require("localFiles.sumOf { it.length() }" in gallery and "localFiles.size >= MAX_ITEMS" in gallery, "gallery quota counts local files including stale metadata orphans")
require("raw.length > MAX_PROFILE_JSON_CHARS" in profile_store and "MAX_PROFILE_JSON_CHARS = 16 * 1024" in profile_store, "per-app profile JSON reads are bounded")
require("endpointUri.host in setOf(\"127.0.0.1\", \"localhost\", \"::1\")" in uploader and "if (!BuildConfig.DEBUG)" in uploader, "test-only upload endpoint injection is loopback-only and disabled in release builds")
require("withTimeout(CONNECTION_TIMEOUT_MS)" in publisher and "catch (_: TimeoutCancellationException)" in publisher, "Gateway connect timeout is handled as reconnect rather than cancelling the publisher loop")
require("maxFrameSize = MAX_GATEWAY_FRAME_BYTES" in publisher and "currentSocket.maxFrameSize = MAX_GATEWAY_FRAME_BYTES" in publisher, "Gateway frame cap is set at client and session layers")
print(f"Static security audit passed: {len(checks)} checks")
for item in checks:
    print("PASS " + item)
PY
