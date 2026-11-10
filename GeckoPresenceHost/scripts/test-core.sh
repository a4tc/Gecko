#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
JAVA="${JAVA:-java}"
OUT="${TMPDIR:-/tmp}/gecko-presence-core-tests.jar"

"$KOTLINC" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/PresenceRules.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/PresenceUpdate.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/BridgePayloadMapper.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/PresenceSourceArbiter.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/AppActivityRules.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/ForegroundEventReducer.kt" \
  "$ROOT/tests/PresenceRulesSelfTest.kt" \
  -include-runtime -d "$OUT"
"$JAVA" -jar "$OUT"
