#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$ROOT/.pure-kotlin-regression"
rm -rf "$TMP"
mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT

kotlinc \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/model/Models.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/AccessibilityJoinMatcher.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/AccessibilityScreenClassifier.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ShizukuUiDumpParser.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/VisualActionButtonPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ShizukuRuntimePolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ShizukuFastUiPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ShizukuContinuityPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ShizukuActivityProofPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ShizukuLaunchPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ActionThrottle.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/AutomationPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ForegroundTargetPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ContinuousHandoffPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ConversationFastExitPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/HybridBackendPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ProfileControlPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/CommunityTraversalMatcher.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/CommunityTraversalPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/TerminalEscapePolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/InvitationStabilityPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/AdaptiveInteractionPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/ScreenEvidencePolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/AccessibilityActionScoringPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeIntelligencePolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeScreenFingerprint.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeCircuitBreaker.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeDecisionCoordinator.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeReplayEngine.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeWatchdogPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeCadencePolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeRecoveryPolicy.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeIdempotencyGuard.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeFeatureFlags.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/util/RuntimeHealthMonitor.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/AutomationSchedule.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/AutomationState.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/SessionRules.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/WhatsAppLinkParser.kt" \
  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/NativeProfileEnginePolicy.kt" \
  "$ROOT/scripts/PureKotlinRegressionMain.kt" \
  -include-runtime \
  -d "$TMP/regressions.jar"

java -jar "$TMP/regressions.jar"
