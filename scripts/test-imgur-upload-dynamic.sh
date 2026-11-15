#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlinc \
  "$ROOT/app/src/main/java/app/geckopresence/host/core/ArtworkMediaPolicy.kt" \
  "$ROOT/tests/dynamic-stubs/ArtworkGalleryStoreStub.kt" \
  "$ROOT/tests/dynamic-stubs/JSONObjectStub.kt" \
  "$ROOT/app/src/main/java/app/geckopresence/host/ImgurAnonymousUploader.kt" \
  "$ROOT/tests/ImgurUploaderDynamicSelfTest.kt" \
  -d "$OUT/test.jar"
kotlin -cp "$OUT/test.jar" app.geckopresence.host.ImgurUploaderDynamicSelfTestKt
