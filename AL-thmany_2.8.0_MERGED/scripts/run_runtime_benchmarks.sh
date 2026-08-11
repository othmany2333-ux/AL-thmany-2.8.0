#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$ROOT/.runtime-benchmark"
rm -rf "$TMP"
mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT

kotlinc \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/model/Models.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/AutomationPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/WhatsAppLinkParser.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeScreenFingerprint.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeIdempotencyGuard.kt" \
  "$ROOT/scripts/RuntimeBenchmarkMain.kt" \
  -include-runtime \
  -d "$TMP/benchmark.jar"

java -jar "$TMP/benchmark.jar"
