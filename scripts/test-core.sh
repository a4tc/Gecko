#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
JAVA="${JAVA:-java}"
OUT="${TMPDIR:-/tmp}/gecko-presence-core-tests.jar"

"$KOTLINC" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/PresenceRules.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/ArtworkMediaPolicy.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/PresenceActivityMapper.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/AppPresenceProfile.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/PresenceUpdate.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/PresenceSourceArbiter.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/AppActivityRules.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/ForegroundEventReducer.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/RpcStartupPolicy.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/DiscordTokenSecurity.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/PresenceActivity.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/DiscordGatewayProtocol.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/GatewayCloseCodePolicy.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/GatewayInvalidSessionPolicy.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/SensitiveDataRedactor.kt" \
  "$ROOT/tests/PresenceRulesSelfTest.kt" \
  "$ROOT/tests/DiscordTokenSecuritySelfTest.kt" \
  "$ROOT/tests/DiscordGatewayProtocolSelfTest.kt" \
  "$ROOT/tests/AppPresenceProfileSelfTest.kt" \
  "$ROOT/tests/GatewayFuzzSelfTest.kt" \
  "$ROOT/tests/ArtworkMediaPolicySelfTest.kt" \
  -include-runtime -d "$OUT"
"$JAVA" -cp "$OUT" app.geckopresence.host.core.PresenceRulesSelfTestKt
"$JAVA" -cp "$OUT" app.geckopresence.host.core.DiscordTokenSecuritySelfTest
"$JAVA" -cp "$OUT" app.geckopresence.host.core.DiscordGatewayProtocolSelfTest
"$JAVA" -cp "$OUT" app.geckopresence.host.core.AppPresenceProfileSelfTestKt
"$JAVA" -cp "$OUT" app.geckopresence.host.core.GatewayFuzzSelfTestKt
"$JAVA" -cp "$OUT" app.geckopresence.host.core.ArtworkMediaPolicySelfTestKt
