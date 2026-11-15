#!/usr/bin/env sh
set -eu

VERSION="8.10.2"
DIST_NAME="gradle-${VERSION}-bin.zip"
BASE_URL="https://services.gradle.org/distributions"
USER_HOME_DIR="${GRADLE_USER_HOME:-${HOME}/.gradle}"
CACHE_DIR="${USER_HOME_DIR}/manual-wrapper/gradle-${VERSION}"
GRADLE_BIN="${CACHE_DIR}/gradle-${VERSION}/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$CACHE_DIR"
  TEMP_DIR="${CACHE_DIR}/download"
  mkdir -p "$TEMP_DIR"
  ZIP_PATH="${TEMP_DIR}/${DIST_NAME}"
  SHA_PATH="${TEMP_DIR}/${DIST_NAME}.sha256"

  if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required to download Gradle ${VERSION}." >&2
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "unzip is required to unpack Gradle ${VERSION}." >&2
    exit 1
  fi

  echo "Downloading Gradle ${VERSION}..."
  curl -fL --retry 3 --connect-timeout 20 "${BASE_URL}/${DIST_NAME}" -o "$ZIP_PATH"
  curl -fL --retry 3 --connect-timeout 20 "${BASE_URL}/${DIST_NAME}.sha256" -o "$SHA_PATH"
  EXPECTED_SHA="$(tr -d '[:space:]' < "$SHA_PATH")"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA="$(sha256sum "$ZIP_PATH" | awk '{print $1}')"
  else
    ACTUAL_SHA="$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')"
  fi
  if [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
    echo "Gradle distribution checksum verification failed." >&2
    rm -f "$ZIP_PATH"
    exit 1
  fi
  unzip -q "$ZIP_PATH" -d "$CACHE_DIR"
  rm -rf "$TEMP_DIR"
fi

exec "$GRADLE_BIN" "$@"
