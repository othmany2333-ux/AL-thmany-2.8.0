## 2.8.0 — Merged Runtime Recovery

- Merged the 2.7.5 Visual Join / Continuous Handoff runtime with the safer 2.4.4 run-state behavior.
- Restored the Accessibility run-state guard before screen processing.
- Replaced the silent package-mismatch stop with conservative runtime target recovery and debounced outside-target handling.
- Preserved explicit Personal/Business/Clone target selections; only AUTO/stale matching bindings can recover automatically.

## 2.7.5 — Accessibility Visual Join & Fast Existing-Chat Handoff

- Added an Android Accessibility screenshot fallback for WhatsApp invitation controls that are visibly rendered but absent from the semantic Accessibility tree.
- Declared `android:canTakeScreenshot="true"` for the Accessibility service, required by Android 11+ screenshot APIs.
- Reused the protected wide WhatsApp-green button detector; no fixed coordinates are introduced.
- Added bounded visual tap verification/retry and explicit screenshot-failure handling.
- Added immediate sequential handoff for invite links that open an already-joined group conversation directly.
- Preserved restriction/loading/terminal guards and the existing semantic Join/Request path as the primary fast lane.

## 2.7.4 — Direct Join/Request &amp; Sequential Handoff Repair

- Uses a real Accessibility gesture inside the semantically selected Join/Request bounds before node ACTION_CLICK, with a distinct idempotency key for each bounded retry.
- Self-disables stale Shadow mode inside both active runtimes so an explicit run can never remain observation-only.
- Adds an exact-foreground screenshot compatibility lane for Samsung Work/Secure profiles whose UiAutomation tree remains on Launcher.
- The visual lane accepts only a wide WhatsApp-green lower-screen action, rejects floating buttons/fixed coordinates, verifies disappearance, retries once, closes the result surface, and advances.
- Restores explicit request/join surface dismissal before direct next-link handoff.
- Reduces the fast non-loading liveness bound to 1.0 second while retaining 20-second genuine Loading protection and restriction hard stops.

## 2.7.3 — Samsung Accessibility &amp; Work Runtime Repair

- Allows an explicit Accessibility run to start when Android reports the exact service component enabled, instead of deadlocking on a delayed Samsung in-process callback.
- Treats either the live callback or a fresh same-profile heartbeat as local connection evidence; service creation and the first event self-heal both signals.
- Adds a bounded 180ms Work Profile compatibility probe when persistent Shizuku UiAutomation remains on Launcher/SystemUI or NO_ROOT.
- Switches to the standard semantic `uiautomator dump` path only after it proves exact WhatsApp package nodes; input remains package, bounds, foreground, and confidence guarded.
- Accepts an exact visible Join/Request label flattened into a non-clickable TextView, with two-scan consensus before coordinate input inside that label.
- Makes Work Controller select an authorized Shizuku runtime when an external DPC owns the profile, while retaining true allowlist control for self-owned profiles.
- Keeps zero-delay direct handoff, 1,000-link explicit batches, Loading protection, restriction hard stops, and no blind cross-profile click.

## 2.7.2 — Live Service &amp; Reliable Launch Repair

- Replaced stale secure-setting readiness with an authoritative live Accessibility-service process signal; a service from an old install or another profile can no longer look ready.
- Fixed the direct Accessibility-details Intent to pass Android's expected flattened service component string, improving exact-page routing on Samsung/AOSP Settings.
- Added a one-time executable-runtime migration and explicit Start guard that clear persisted developer Shadow mode before a real run.
- Kept the proven 2.5.1 event-first 14/36/95 ms cadence, exact-package semantic actions, Loading protection, and zero-delay direct handoff.
- Validates ActivityManager launch output because `am start` may return exit code 0 with a semantic error; a failed package launch retries once through the exact resolved WhatsApp VIEW activity.
- Caches a proven resolved WhatsApp activity after recovery so following links retain direct no-wait speed.
- Preserves exact Android user/package locking, restriction hard stops, and bounded unknown-screen advancement.

## 2.7.0 — Adaptive 1000-Link Runtime

- Raised the explicit run window to 1,000 links.
- Added shared 0–10,000 ms inter-link speed control with 100 ms precision.
- Accelerated Accessibility event cadence to 14/36/95 ms with 68 ms click throttling.
- Restored adaptive persistent Shizuku probing with automatic compatible command fallback.
- Added exact-user WhatsApp Conversation activity proof for direct post-Join handoff.
- Removed the Shizuku Back wait from verified conversation-to-next-link transitions.
- Expanded current WhatsApp Arabic/English semantic and resource-ID matching.
- Refreshed the main dashboard with a deep-teal header and cleaner professional cards.

## 2.6.4 — Fast Transition & Permission Stability Core
- Fixed the biggest Shizuku Work Profile latency regression: healthy persistent frames that still belong to AL-thmany/system UI during a deep-link transition no longer count as fast-UI failures and no longer trigger heavyweight `uiautomator dump` after four frames.
- Arms the persistent UiAutomation event path even from `NO_ROOT`/pre-target transition states, then waits for the exact WhatsApp package event/tree instead of starting a command-dump process; events that arrive during the initial arm are preserved against the per-link launch baseline.
- Expires the optimistic exact-user foreground lease after 480 ms of a healthy but target-hidden transition, forcing one profile-aware foreground re-proof/reopen instead of silently waiting on the wrong root.
- Serializes Shizuku terminal-result commits so counters and queue advancement remain exactly-once even if multiple detectors observe the same terminal frame.
- Hardens the Accessibility one-time permission gate: three consecutive disabled reads are required before reopening setup; one transient Samsung/Android false-negative after update/resume is ignored.
- Keeps 0 ms next-link settle, 14/36/95 ms event cadence, Request terminal direct handoff, Loading protection, exact-user lock, and Restriction hard-stop unchanged.

## 2.6.3 — Accessibility Permission Gate Fix
- Stops the one-time Accessibility setup dialog from repeating when Android already reports the service enabled.
- Separates permission configuration from local service-process heartbeat readiness.
- Waits for the local Accessibility service bind every 120 ms for up to 3 seconds after update/resume before reporting a reconnect issue.
- Keeps profile-local execution safety: automation still requires the local service heartbeat before Accessibility execution.

# AL-thmany 2.6.2 — Request Terminal Direct Handoff

- Fixes the Work Profile case captured on video where Request to join succeeds, WhatsApp shows “request sent / waiting for admin approval”, but the run remains parked on the first invite.
- When the current link has a confirmed REQUEST input, a visible Cancel-request control or explicit request-sent text is treated as terminal REQUESTED evidence and advances immediately.
- Adds a request-only bounded shell UI probe when persistent UiAutomation receives a post-request event but exposes an UNKNOWN/partial tree. The normal Join hot path remains persistent/event-first.
- Keeps zero next-link settle, exact-user lock, Loading protection, and Restriction hard stop unchanged.

# AL-thmany 2.6.1 — Unstoppable Sequential Continuity

- Added a continuity state machine for `SHIZUKU_FAST_UI_ACTIVE` so normal UI states cannot strand the queue on one link.
- A post-launch WhatsApp conversation is accepted as direct `ALREADY_MEMBER` only after a new target event newer than the deep-link launch baseline, preventing stale-chat false positives.
- Foreground loss performs two bounded exact-user deep-link reacquisition attempts, then records an explicit unknown result and advances instead of stopping.
- Persistent `NO_ROOT`, fallback UI-tree failures, ambiguous action exhaustion, and `STOP_UNKNOWN` now advance safely without low-confidence taps.
- Repeated input failures no longer terminate the service; the bounded action watchdog remains authoritative and advances safely.
- Restriction / Retry Later remains a mandatory hard stop; profile/permission security boundaries are not bypassed.
- Zero-delay direct handoff, Loading protection, Community traversal, and exact-user/package locking remain intact.

# AL-thmany 2.6.0 — Accessibility Parity Fast Path

- Replaced persistent Shizuku fast-path XML serialization/parsing with a compact node frame while preserving the same node evidence used by the classifier.
- Cached exact clickable AccessibilityNodeInfo objects inside the shell-owned UiAutomation session so ACTION_CLICK can reuse the node captured by the scan instead of traversing the tree a second time.
- Removed the fast-mode open settle; exact-user launch lease plus target-package node evidence remains mandatory.
- Restored the requested conservative fast cadence (14/36/95 ms, 68 ms click throttle, 18 ms gesture, 88 ms result analysis) while removing architectural overhead rather than chasing smaller timers.
- Instant next-link handoff remains 0 ms. Loading protection and Restriction hard-stop remain unchanged.

## 2.5.2 — Event-Coalesced Node-Action Turbo
- Added `waitAndSnapshot`: event wait + active-tree snapshot in one Shizuku UserService Binder round-trip.
- Added direct `AccessibilityNodeInfo.ACTION_CLICK` on the exact-package clickable node under the selected action; persistent coordinate injection and shell input remain fallbacks.
- Avoids heavyweight command dumps during transient fast-profile activity transitions unless the persistent bridge repeatedly fails.
- Faster adaptive cadence: 4ms event-tree coalescing, 6ms event response, 24ms stable scan, 72ms fallback poll, 36ms click throttle, 60ms result fallback, 1.0s non-loading watchdog and 0ms next-link settle.
- Loading remains protected at 20s; ambiguous actions keep multi-scan consensus; WhatsApp Restriction/Retry Later remains a hard stop.

## 2.5.1 — Event-First SHIZUKU_FAST_UI_ACTIVE
- Applies the requested 2.4.4-style cadence only to the persistent Shizuku fast UI path: 14ms event scan, 36ms stable scan, 95ms fallback poll, 40ms post-action watchdog, 68ms click throttle, 18ms gesture, 120ms retry, 35ms joined-conversation evidence, and zero-delay direct next-link handoff.
- Adds a UiAutomation accessibility-event listener through the Shizuku UserService so fast scans are event-first rather than timer-first.
- After a verified joined conversation, the next deep link opens directly without waiting for Back; Loading retains a protected 20s budget and Restriction/Retry Later remains a hard stop.

## 2.5.0 — Persistent UiAutomation Turbo Bridge
- Added an optional persistent `UiAutomation` session inside the Shizuku UserService. When Android exposes it, AL-thmany reads the active accessibility node tree directly instead of spawning `uiautomator dump` for every scan.
- Added direct persistent tap and Back injection, while preserving the existing `input tap` / `input keyevent` fallback.
- Added automatic capability fallback: if the persistent session is blocked by the platform/Knox, the 2.4.3 command-based engine continues without losing the current link.
- Added adaptive transition bursts: 12 ms scans during link/action transitions, 45 ms idle scans after the screen stabilizes, reducing both latency and unnecessary CPU load.
- Reduced Shizuku input cooldown to 32 ms and increased the verified foreground lease to 8 seconds while still requiring exact user + exact WhatsApp package evidence.
- Removed two redundant database queries from every successful result handoff and throttled non-terminal notification refreshes.
- Kept one-scan execution only for exact-package, clickable, high-confidence actions; ambiguous actions and community rows still require multi-scan consensus.
- Restriction / retry-later screens remain hard stops; no Knox/DPC bypass was added.

## 2.4.3 — Accessibility-Like Direct Handoff
- Ported Accessibility-style successful Join handoff to Shizuku: fast Back pulse, exactly-once result commit, then direct next-link launch.
- Removed `am start -W` launch waiting; exact-user/package launch is followed immediately by a package-validated UI dump.
- Added a short exact-user foreground lease immediately after a successful target launch, avoiding a heavy `dumpsys` probe before the first UI snapshot.
- Throttled full result-mirror rewrites to every 10 results (plus terminal runs), matching the Accessibility engine and removing a major per-link stall.
- Reduced explicit settle/cooldown delays while preserving semantic one-scan gating only for high-confidence actions.
- Restrictions/Retry Later remain hard stops; exact-user lock and Knox/DPC non-bypass guarantees remain unchanged.

# AL-thmany 2.4.2 — Accessibility-Like Shizuku Fast Path

- Added a high-confidence one-scan Shizuku action path for exact-package clickable View/Join/Request nodes.
- Added a short exact-user foreground lease after ActivityManager/UI-dump proof to avoid redundant dumpsys calls before every input.
- Reduced Shizuku input cooldown and fixed settle/scan delays while retaining ambiguity, bounds, package and restriction guards.
- Fast post-join verification now accepts one strong WhatsApp surface after a short minimum age; ambiguous/request confirmation paths remain conservative.
- No Knox/DPC or WhatsApp restriction bypass was added.

# AL-thmany 2.4.1 — Profile-Aware Foreground Fix

- Fixed Shizuku Work Profile foreground detection: no longer trusts the first `dumpsys window` focus line.
- Uses ActivityManager resumed-activity evidence carrying the exact Android user token (`u10`, `uNN`) before authorizing UIAutomator/input.
- Keeps exact-user lock strict; package-only window focus remains diagnostic only.
- Foreground timeouts now include a compact activity/window probe summary for diagnosis.
- Preserves current link and WhatsApp restriction hard stops.

## 2.4.0 — Native Multi-Profile Engine
- Added profile-aware native backend router.
- Added optional Work Profile DevicePolicyController receiver and explicit allowlist assistance when AL-thmany is the actual Profile Owner.
- Added a unified Settings capability card for Personal / Work / Samsung isolated profiles.
- AUTO now prefers profile-local Accessibility, then guarded Shizuku; Secure Folder never assumes Knox bypass.
- Existing restriction/retry-later hard stop and current-link preservation remain unchanged.

## 2.3.3 - Work Profile Settings & Policy Probe

- Added three explicit Accessibility settings routes: AL-thmany service details, the Accessibility services list, and current-profile app info.
- Added a profile-local policy inspector for service visibility, enabled-service state and local binding.
- Added honest Device Policy visibility: exact allowlist status is reported only when Android grants owner-level access; normal apps no longer guess.
- Added managed-profile activation states including parent-toggle-required and enabled-setting-present-but-not-active.
- The main toolbar now displays the actual BuildConfig version.
- No Knox, Work Profile, or Device Policy bypass is attempted.

## 2.3.2 - Profile Accessibility Fix

- Added a per-profile Accessibility service heartbeat so a service enabled in the personal profile can no longer be mistaken for a locally connected Work/Secure service.
- Start/readiness checks now require profile-local Accessibility connectivity.
- Added direct accessibility-service detail launching using the service component, with safe fallback to standard Settings.
- Added live profile-control diagnostics without storing messages, invite URLs, contacts, or chat content.
- Secondary/isolated profiles preserve the current link if the Accessibility UI tree stays unavailable instead of consuming the queue without a click.
- Preserved WhatsApp restriction hard-stop behavior and did not add any Knox/MDM bypass.

## 2.3.1 - Continuity Recovery Core

- Restored Accessibility-first continuity so a stale/dead Shizuku runtime flag cannot disable personal-mode joining.
- Added automatic Shizuku → Accessibility fallback for the current run when Accessibility is actually enabled.
- Added fast request-surface exit and joined-conversation Back pulse before immediate next-link handoff.
- Added profile-key continuity validation to every invitation launch.
- Preserved restriction/retry-later hard stop and kept Shizuku isolated for profiles where Accessibility is unavailable.

## 2.3.0 — Smart Hybrid Runtime

- Added fail-closed Shizuku preflight before any queue item is consumed: Binder, permission, UserService, shared Android user/profile, shell and display bounds must all be available.
- Added package-aware UIAutomator action scoring with ambiguity rejection; candidate nodes from another package are ignored.
- Added two-scan semantic consensus before Shizuku taps and bounded input idempotency/cooldown to prevent duplicate or stale clicks.
- Added display-bound validation before every tap and bounded swipe/back input.
- Strengthened Join verification: leaving an invite is not enough; a stable WhatsApp home, conversation, or Community surface is required.
- Unknown/ambiguous Shizuku states now stop with the current link preserved instead of guessing a failure.
- Added Shizuku runtime heartbeat and automatic fail-closed handling if Binder/UserService capability disappears mid-run.
- Added bounded Shizuku Community Traversal when UIAutomator actually exposes the Community list: semantic subgroup rows, two-scan row consensus, processed-row de-duplication, bounded scroll/back recovery, and no-progress termination.
- Announcements, add/create/manage/settings/invite/leave/report/delete/remove/block controls remain excluded from Community subgroup candidates.
- Restriction/Retry-later remains a mandatory hard stop; no WhatsApp, Knox or DPC restriction bypass is attempted.
- External explicit-run limit remains 150 links.

## 2.2.0 — Shizuku Hybrid Engine
- Added an optional Shizuku 13.1.5 UserService backend alongside the existing Accessibility engine.
- Added AUTO / ACCESSIBILITY / SHIZUKU runtime selection with no double-execution between engines.
- Added a semantic UIAutomator XML parser that reuses existing invite classification and safe Join/Request/terminal rules.
- Added foreground-package verification before every injected tap and same-Android-user resolution before opening a link.
- Added Shizuku Binder/permission/shell/UI-dump probe and explicit fail-closed behavior when Knox/Work blocks the UI hierarchy.
- Shizuku backend uses semantic node bounds only; no blind fixed-coordinate join clicks.
- Restriction/Retry-later remains a mandatory hard stop; no Knox/DPC bypass is attempted.
- External explicit-run limit remains 150 links. Community subgroup traversal remains Accessibility-only in 2.2.0 until device UI-dump capability is proven.

## 2.1.0 — Profile & Community Core
- Added profile-local WhatsApp target discovery and validation for owner, Work/Managed, Samsung isolated/Secure Folder-like, and secondary Android profiles.
- Added strict explicit package launch and a per-run package + profile target lock to prevent accidental handoff to another WhatsApp installation.
- Added a target-test control and explicit-target requirement in isolated profiles.
- Added bounded Community Traversal: enter Community, discover semantic subgroup rows, process Join/Request/already-member/terminal states with the existing safe engine, return, scroll, and continue.
- Added persistent Community checkpoint state and exactly-once subgroup-row tracking.
- Announcements, add/create/manage/settings/invite/leave/report/delete/remove/block controls are excluded from subgroup candidates.
- Scheduled runs validate and lock the profile-local WhatsApp target before consuming any queue item.
- Restriction/Retry-later screens remain mandatory hard stops. External explicit-run limit remains 150 links.

## 2.0.0 — Adaptive Recovery Core
- Replaced ultra-aggressive 4ms/30ms Turbo scanning with event-first adaptive cadence (18ms event scan, 120ms liveness poll, stable-screen relaxation).
- Added root-unavailable self-recovery so a missing Accessibility tree cannot park a link indefinitely.
- Preserved the explicit run across temporary Accessibility `onInterrupt()` when Auto Resume is enabled.
- Added actionable stalled-screen recovery that advances safely without clicking unknown controls.
- Hardened post-Join/Request handoff with fingerprint stability before timeout force-advance.
- New installs default to no automatic pause merely because foreground leaves WhatsApp; manual pause and the explicit setting remain available.
- Restriction screens remain hard-stop conditions; explicit run remains capped at 150 links.


## 1.9.1 — 150-Link Explicit Run
- Increased the bounded explicit-run window from 80 to **150 links**.
- Kept the 1,000,000-link disk-backed queue, de-duplication, progress persistence, pause/resume, and restriction-stop behavior unchanged.
- Updated Arabic/English UI copy, source validation, unit expectations, and pure-Kotlin regressions to the new 150-link window.
- Instant Terminal Router behavior is unchanged.
# AL-thmany 1.9.0 — Instant Terminal Router

- Adds a first-frame Terminal Escape policy for specific final WhatsApp invitation outcomes.
- Reset/expired/revoked links, full groups, removed/banned accounts, pending requests, and already-member states now outrank stale Loading/action nodes.
- These specific terminal outcomes bypass repeated stability/confidence waits while WhatsApp restrictions still stop the run.
- Terminal Escape bypasses the inter-link delay and opens the next invitation immediately after the bounded verified dismiss chain.
- Adds Android standard positive-button ID recognition for terminal acknowledgements.
- Reduces Turbo terminal dismiss settle to 35 ms while keeping bounded semantic/Back fallbacks.

## 1.8.2 - Verified Terminal Escape
- Fixed terminal reset/invalid invite sheets that could remain visible in Turbo mode.
- Terminal acknowledgement is now preferred, disappearance is verified, and a guarded Back fallback is used when needed.
- Turbo exit sequence is bounded to 3 fast attempts with a terminal-only 90 ms settle.

# AL-thmany Changelog

## 1.8.1 - Smart Terminal Escape
- Added screen-wide terminal-state aggregation so WhatsApp error sentences split across multiple Accessibility nodes are still recognized.
- Reset/expired/revoked invite errors now advance even when the sentence is split before “رابط الدعوة”.
- Added structural recognition for removed/banned, full/limit-reached, missing group/community, and generic “cannot join” variants.
- A terminal “موافق / OK” remains a dismissal control only; it is never treated as a fresh join confirmation once terminal evidence exists.
- Split request-pending text is recognized across nodes, while the pre-request admin-approval notice still does not count as a submitted request.
- Terminal results are persisted once, dismissed through X/OK/Back as available, then handed off to the next unique invite without parking the run.

## 1.8.0 - Continuous Handoff
- Added an independent continuity watchdog after Join/Request actions so quiet Accessibility event streams cannot park a run indefinitely.
- Turbo mode opens the next invite directly from a joined conversation instead of depending on Android Back.
- Pending-request evidence hands off immediately to the next invite; Cancel request is never clicked.
- Non-loading unverified action states have a bounded liveness window, then are recorded as timeout and skipped safely.
- Runtime circuit-breaker thresholds are diagnostic only during the explicit run; known restrictions still stop immediately.
- Loading screens remain protected and are never force-skipped.

# AL-thmany 1.7.1 — Complete Repair Hotfix

- Fixes the 1.7 overlay packaging omission that could leave `ForegroundTargetPolicy.kt` absent in repositories upgraded from older baselines.
- Complete repair overlay now includes the full `app/src/main`, `app/src/test`, scripts, workflow, and Gradle configuration needed by validation and CI.
- Runtime behavior and UltraMotion speed policy remain the same as 1.7.0.
- Keeps the foreground transient-window guard so keyboards/resolvers do not incorrectly pause an active WhatsApp run.

# AL-thmany 1.7.0 — UltraMotion Precision

- Faster event-first scans, clicks, gesture fallbacks, exit settle, and result handoff in Turbo mode.
- Added bounded semantic invitation scrolling when a valid control is below the visible fold.
- Reduced post-loading and home-surface settle time while keeping loading/restriction guards intact.
- Preserves Precision Fusion matching, idempotency, single-flight execution, and user-selected inter-link delay.

# AL-thmany 1.6.0 — Precision Fusion

- Fused the strongest invitation-state matching from JoinPilot Nova 12.6 into the newer AL-thmany runtime without restoring 12.6 conservative delays.
- Added semantic group-vs-community target detection for diagnostics and safer scoring context.
- Added exact Arabic screenshot variants for reset invitations and removed-account screens, including “حيث قد تمت إزالتك منها”.
- A visible **إلغاء الطلب / Cancel request** now counts as an already-pending request only when WhatsApp is not simultaneously offering a fresh Request-to-join action.
- Separated terminal **موافق / OK** acknowledgements from positive Join/Request/Confirm actions so error dialogs no longer create false action conflicts.
- After a terminal result is persisted, the exit routine may dismiss the dialog through X, safe OK/موافق, Back, then the existing guarded fallback.
- Retains Loading Guard, Context Guard, Confidence Engine, Single-flight execution, idempotency, automatic resume, notification controls, and zero artificial inter-link delay at user speed 0.
- Keeps the existing one-million-link disk-backed queue and 80-link explicit run window; no stealth/evasion or automatic mass joining of every subgroup in a community was added.

# AL-thmany 1.5.0 — Lightning Resume Compact

- Added stable-foreground departure guard to prevent false pauses caused by transient Android windows while WhatsApp remains foreground.
- Faster turbo handoff: 40ms poll fallback, 8ms minimum scan spacing, 60ms result inference, 8ms exit settle, and zero artificial next-link settle at user speed 0.
- Turbo post-Join non-invite verification can complete after one strong scan; normal mode remains conservative.
- Persistent runtime notification refresh with a new notification channel and Pause/Resume/Stop actions.
- Resume-from-saved-progress expanded to user stop, service restart, action timeout, open failure, browser fallback, unknown-screen stop, and circuit-breaker stop; restriction stops remain excluded.
- Compact main dashboard and installed-WhatsApp picker-first flow.
- Disk-backed queue cap increased to 1,000,000 unique links while the explicit run window remains 80.

# AL-thmany 1.4.1 — Android Lint API Guard Hotfix

- Fix Android Lint fatal error for UserManager.isManagedProfile by guarding the call with Build.VERSION.SDK_INT >= Android R (API 30).
- Keeps minSdk 26 and preserves profile-local detection behavior on supported Android versions.
- No runtime speed or queue behavior changed.

# AL-thmany 1.4.0 — Adaptive Multi-WhatsApp Runtime

- Added an installed-app WhatsApp picker that discovers WhatsApp-capable apps in the current Android profile instead of relying only on fixed package names.
- Added profile-local Work/Managed/secondary-profile awareness; Android security still requires AL-thmany and Accessibility to be enabled inside the same profile as the target WhatsApp app.
- Added automatic pause when the foreground leaves the selected WhatsApp target, with automatic resume on return only when Auto Resume is enabled and the pause was not manual.
- Notification Pause/Resume/Stop remains available during the explicit run.
- Added return-to-dashboard behavior when the explicit run window or full queue completes.
- Turbo path tightened: 60 ms poll, 15 ms minimum scan interval, 70 ms click throttle, 100 ms result inference, 15 ms exit settle, 5 ms zero-delay handoff guard.
- Strong, conflict-free Join/Request controls may act after one turbo scan; ambiguous screens still require repeated evidence.
- Turbo unknown/home recovery reduced while loading keeps its separate conservative budget.
- Queue capacity increased to **250,000 unique links** while the explicit run window remains 80.
- Main dashboard now exposes Auto Resume, pause-on-exit, return-on-complete, target-app selection, speed and timing in the same scrollable page.

# AL-thmany 1.3.0 — Turbo Stability & Auto Resume

- Added user-controlled **Automatic Resume** for the current explicit run after transient Accessibility-service recreation.
- Preserves the current link/stage in SharedPreferences + SQLite and resumes from the same saved position; it never extends the explicit-run cap.
- Turbo timings reduced: 90 ms poll, 25 ms minimum scan interval, 100 ms click throttle, 150 ms fast result inference, 25 ms exit settle, 10 ms instant handoff guard.
- Fast invite exit uses at most two close/back steps instead of four.
- Short-delay waits use 10 ms slices instead of fixed 20 ms polling.
- Session queue capacity expanded from 5,000 to **250,000 unique links** while keeping the explicit run window at 80.
- Input/import budget expanded for large queues; de-duplication and SQLite-backed progress remain enabled.
- Restriction, loading, conflict, idempotency, and circuit-breaker guards remain unchanged.

# AL-thmany 1.2.2 — User Speed Control Pro

- The user can select 0–60 seconds between completed links; 0 means immediate next-link transition after an 80 ms UI-settle guard.
- Removed the hidden 3-second post-join wait in fast mode; strong conversation evidence can be accepted after 450 ms.
- Fast runtime inference reduced to 450 ms and fast scan/click throttles tuned for responsive transitions.
- Loading and action-timeout safety budgets remain independent, so a slow WhatsApp loading screen is not misclassified just because next-link speed is set to 0.
- All terminal and normal advances now respect the same user-selected speed.

# AL-thmany 1.2.1 — Unit Test Compatibility Hotfix

- Fixed Android unit-test compilation after Smart Runtime Pro introduced `candidateScoreMargin`.
- Made `candidateScoreMargin` backward-compatible with a safe default of `0`; production Accessibility calls still pass the real score margin explicitly.
- No change to runtime batch limits, restriction handling, or automatic action safety boundaries.

# Changelog

## 1.2.0 — Smart Runtime Pro

- محرك قرار موحد `RuntimeDecisionCoordinator` بأولوية صارمة: تقييد، تحميل، تعارض، نتيجة نهائية، إجراء، ثم unknown.
- Confidence Engine قابل للتفسير قبل النقر وقبل اعتماد النتائج النهائية.
- Action Scoring للسياق والعقد القابلة للنقر وطلب موافقة المشرف مع هامش بين أفضل مرشحين.
- Screen Fingerprint مستقل عن ترتيب traversal لتثبيت فهم الشاشة وكشف التعليق.
- Single-flight action lock وIdempotency Guard لمنع النقرات المتداخلة والمكررة.
- Watchdog للشاشات الثابتة وCircuit Breaker يتوقف بعد 6 أخطاء بنيوية متتالية.
- Shadow Mode للتشخيص بدون ضغط، وRuntime Replay Engine لاختبارات القرار خارج Android.
- Diagnostic Journal محلي دوّار قابل للمشاركة/المسح من الإعدادات ولا يسجل محتوى المحادثات.
- Live Runtime Health يعرض directive/action/confidence/screen stability/watchdog في لوحة التشغيل بدون تخزين محتوى WhatsApp.
- SQLite schema v5 مع فهرس `session/status/position` واستعلام Dashboard خفيف بنافذة 120 رابطًا.
- MainViewModel لا يحمل جميع الروابط في مسار التشغيل الساخن؛ التقرير الكامل يُحمّل عند الطلب فقط.
- تقليل ضجيج Accessibility إلى أحداث النافذة/المحتوى/النقر مع `notificationTimeout=120ms`.
- إضافة Runtime micro-benchmark إلى GitHub Actions لاختبار workload كبير بدون threshold زمني هش.
- توسيع مطابقة Join/Request/Loading/Restriction بصيغ عربية وإنجليزية إضافية.
- Loading Guard محسن: لا X ولا Back أثناء شاشة التحميل.
- الحفاظ على one-link/one-launch، فحص المحادثة القوي بمسح واحد، والتوقف عند قيود WhatsApp.

## 1.1.0 — Performance & Stability Core

- تسلسل فحص Accessibility واحد مع دمج أحداث واتساب المتلاحقة بدل تشغيل فحوص متوازية.
- استعلام مباشر للرابط الحالي بدل تحميل قائمة تصل إلى 250000 رابط في كل فحص.
- تحسين تحديث حالة الجلسة باستخدام SQL aggregates بدل إعادة بناء كل الروابط.
- مزامنة ملفات Joined/Fail/Left كل 10 نتائج وعند نهاية التشغيل بدل إعادة كتابتها بعد كل رابط.
- إيقاف التحديث الكامل لقائمة الجلسة كل 850ms أثناء التشغيل.
- عرض نافذة ذكية من 120 رابطًا فقط في لوحة التحكم لتجنب تجميد RecyclerView.
- Debounce لتحليل النصوص الكبيرة حتى لا تتجمد الواجهة عند لصق آلاف الروابط.
- قاعدة البيانات تظل المصدر الموثوق للتقدم حتى لو أعاد Android إنشاء الواجهة أو خدمة Accessibility.

## 1.0.0 — AL-thmany Intelligent Core

- إعادة تسمية التطبيق والمشروع إلى **AL-thmany**.
- Android namespace/applicationId جديد: `com.althmany.groupmanager`.
- مستودع نظيف بدون ملفات تحديث وإصدارات متراكمة قديمة.
- الحفاظ على قائمة حتى 250,000 رابط و80 رابطًا لكل تشغيل صريح.
- إضافة تعارض `MULTIPLE_POSITIVE_ACTIONS` لمنع الضغط أثناء ظهور أكثر من زر إيجابي متضارب في انتقالات WhatsApp.
- تقوية اكتشاف واجهة WhatsApp الرئيسية: شريط بحث قوي أو أكثر من دليل تبويب بدل الاعتماد على كلمة Chats/الدردشات وحدها.
- الحفاظ على Loading Guard وContext Guard ومنع إعادة فتح نفس الرابط والتوقف عند القيود.
- إصلاح Unit Test قديم كان ما يزال يتوقع حد 30 رابطًا رغم أن السياسة الحالية 250,000 رابط؛ هذا يمنع فشل GitHub Actions بسبب اختبار قديم غير متزامن.
- تحديث GitHub Actions والـArtifact إلى `al-thmany-debug-apk`.
