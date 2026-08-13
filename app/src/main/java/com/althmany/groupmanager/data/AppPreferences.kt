package com.althmany.groupmanager.data

import android.content.Context
import com.althmany.groupmanager.domain.AutomationPolicy
import com.althmany.groupmanager.domain.AutomationStage
import com.althmany.groupmanager.domain.AutomationStopReason
import com.althmany.groupmanager.domain.AccessibilityInviteTarget
import com.althmany.groupmanager.domain.CommunityTraversalStage
import com.althmany.groupmanager.domain.LinkRuntimePhase
import com.althmany.groupmanager.domain.RestrictionHandlingMode
import com.althmany.groupmanager.domain.RuntimeSpeedMode
import com.althmany.groupmanager.domain.RuntimeSpeedProfile
import com.althmany.groupmanager.domain.RuntimeSpeedProfilePolicy
import com.althmany.groupmanager.domain.SmartResultClassifier
import com.althmany.groupmanager.model.AutomationBackend
import com.althmany.groupmanager.model.PreferredTarget
import com.althmany.groupmanager.model.ThemeMode

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var activeSessionId: String?
        get() = preferences.getString(KEY_ACTIVE_SESSION_ID, null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_ACTIVE_SESSION_ID) else putString(KEY_ACTIVE_SESSION_ID, value)
            }.apply()
        }

    var autoAdvance: Boolean
        get() = preferences.getBoolean(KEY_AUTO_ADVANCE, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_ADVANCE, value).apply()

    var quickJoinNotification: Boolean
        get() = preferences.getBoolean(KEY_QUICK_JOIN_NOTIFICATION, true)
        set(value) = preferences.edit().putBoolean(KEY_QUICK_JOIN_NOTIFICATION, value).apply()

    /**
     * Keep the physical display awake while an explicit automation run is active.
     * Enabled by default and released automatically when the run pauses/stops.
     */
    var keepScreenAwake: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_AWAKE, true)
        set(value) = preferences.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, value).apply()

    var notificationPermissionAsked: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)
        set(value) = preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, value).apply()

    var smartAutoStart: Boolean
        get() = preferences.getBoolean(KEY_SMART_AUTO_START, true)
        set(value) = preferences.edit().putBoolean(KEY_SMART_AUTO_START, value).apply()

    /** Faster event-first scanning while preserving the user-selected inter-link delay. */
    var fastHandsFreeMode: Boolean
        get() = preferences.getBoolean(KEY_FAST_HANDS_FREE_MODE, true)
        set(value) = preferences.edit().putBoolean(KEY_FAST_HANDS_FREE_MODE, value).apply()

    var runtimeSpeedMode: RuntimeSpeedMode
        get() = enumValueOrDefault(
            preferences.getString(KEY_RUNTIME_SPEED_MODE, null),
            RuntimeSpeedMode.FAST
        )
        set(value) = preferences.edit().putString(KEY_RUNTIME_SPEED_MODE, value.name).apply()

    var customScanMs: Int
        get() = preferences.getInt(KEY_CUSTOM_SCAN_MS, 12)
            .coerceIn(
                RuntimeSpeedProfilePolicy.MIN_CUSTOM_SCAN_MS,
                RuntimeSpeedProfilePolicy.MAX_CUSTOM_SCAN_MS
            )
        set(value) = preferences.edit()
            .putInt(
                KEY_CUSTOM_SCAN_MS,
                value.coerceIn(
                    RuntimeSpeedProfilePolicy.MIN_CUSTOM_SCAN_MS,
                    RuntimeSpeedProfilePolicy.MAX_CUSTOM_SCAN_MS
                )
            )
            .apply()

    var customPostTapMs: Int
        get() = preferences.getInt(KEY_CUSTOM_POST_TAP_MS, 55)
            .coerceIn(
                RuntimeSpeedProfilePolicy.MIN_CUSTOM_POST_TAP_MS,
                RuntimeSpeedProfilePolicy.MAX_CUSTOM_POST_TAP_MS
            )
        set(value) = preferences.edit()
            .putInt(
                KEY_CUSTOM_POST_TAP_MS,
                value.coerceIn(
                    RuntimeSpeedProfilePolicy.MIN_CUSTOM_POST_TAP_MS,
                    RuntimeSpeedProfilePolicy.MAX_CUSTOM_POST_TAP_MS
                )
            )
            .apply()

    var customInterLinkMs: Int
        get() = preferences.getInt(KEY_CUSTOM_INTER_LINK_MS, 0)
            .coerceIn(
                RuntimeSpeedProfilePolicy.MIN_CUSTOM_INTER_LINK_MS,
                RuntimeSpeedProfilePolicy.MAX_CUSTOM_INTER_LINK_MS
            )
        set(value) = preferences.edit()
            .putInt(
                KEY_CUSTOM_INTER_LINK_MS,
                value.coerceIn(
                    RuntimeSpeedProfilePolicy.MIN_CUSTOM_INTER_LINK_MS,
                    RuntimeSpeedProfilePolicy.MAX_CUSTOM_INTER_LINK_MS
                )
            )
            .apply()

    fun runtimeSpeedProfile(): RuntimeSpeedProfile =
        RuntimeSpeedProfilePolicy.resolve(
            runtimeSpeedMode,
            customScanMs,
            customPostTapMs,
            customInterLinkMs
        )

    var restrictionHandlingMode: RestrictionHandlingMode
        get() = enumValueOrDefault(
            preferences.getString(KEY_RESTRICTION_HANDLING_MODE, null),
            RestrictionHandlingMode.SKIP_AND_CONTINUE
        )
        set(value) = preferences.edit()
            .putString(KEY_RESTRICTION_HANDLING_MODE, value.name)
            .apply()

    var runtimeLockedAndroidUserId: Int
        get() = preferences.getInt(KEY_RUNTIME_LOCKED_ANDROID_USER_ID, -1)
        private set(value) = preferences.edit()
            .putInt(KEY_RUNTIME_LOCKED_ANDROID_USER_ID, value)
            .apply()

    fun lockRuntimeAndroidUserId(userId: Int): Boolean {
        if (userId < 0) return false
        val current = runtimeLockedAndroidUserId
        if (current >= 0 && current != userId) return false
        runtimeLockedAndroidUserId = userId
        return true
    }

    var runtimeLinkPhase: LinkRuntimePhase
        get() = enumValueOrDefault(
            preferences.getString(KEY_RUNTIME_LINK_PHASE, null),
            LinkRuntimePhase.OPENING
        )
        private set(value) = preferences.edit().putString(KEY_RUNTIME_LINK_PHASE, value.name).apply()

    var runtimeCurrentLinkId: Long
        get() = preferences.getLong(KEY_RUNTIME_CURRENT_LINK_ID, -1L)
        private set(value) = preferences.edit().putLong(KEY_RUNTIME_CURRENT_LINK_ID, value).apply()

    var runtimeCurrentLinkPosition: Int
        get() = preferences.getInt(KEY_RUNTIME_CURRENT_LINK_POSITION, -1)
        private set(value) = preferences.edit().putInt(KEY_RUNTIME_CURRENT_LINK_POSITION, value).apply()

    var runtimeCurrentLinkUrl: String?
        get() = preferences.getString(KEY_RUNTIME_CURRENT_LINK_URL, null)
        private set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RUNTIME_CURRENT_LINK_URL)
            else putString(KEY_RUNTIME_CURRENT_LINK_URL, value.take(512))
        }.apply()

    var runtimeLinkStartedAt: Long
        get() = preferences.getLong(KEY_RUNTIME_LINK_STARTED_AT, 0L)
        private set(value) = preferences.edit().putLong(KEY_RUNTIME_LINK_STARTED_AT, value).apply()

    var runtimeActionExecuted: String?
        get() = preferences.getString(KEY_RUNTIME_ACTION_EXECUTED, null)
        private set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RUNTIME_ACTION_EXECUTED)
            else putString(KEY_RUNTIME_ACTION_EXECUTED, value.take(80))
        }.apply()

    var runtimeActionAttemptCount: Int
        get() = preferences.getInt(KEY_RUNTIME_ACTION_ATTEMPT_COUNT, 0).coerceAtLeast(0)
        private set(value) = preferences.edit()
            .putInt(KEY_RUNTIME_ACTION_ATTEMPT_COUNT, value.coerceAtLeast(0))
            .apply()

    var runtimeRecoveryReopenAttempts: Int
        get() = preferences.getInt(KEY_RUNTIME_RECOVERY_REOPEN_ATTEMPTS, 0).coerceAtLeast(0)
        private set(value) = preferences.edit()
            .putInt(KEY_RUNTIME_RECOVERY_REOPEN_ATTEMPTS, value.coerceAtLeast(0))
            .apply()

    var runtimeLastEngineState: String?
        get() = preferences.getString(KEY_RUNTIME_LAST_ENGINE_STATE, null)
        set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RUNTIME_LAST_ENGINE_STATE)
            else putString(KEY_RUNTIME_LAST_ENGINE_STATE, value.take(120))
        }.apply()

    var lastCompletedLinkPosition: Int
        get() = preferences.getInt(KEY_LAST_COMPLETED_LINK_POSITION, -1)
        private set(value) = preferences.edit()
            .putInt(KEY_LAST_COMPLETED_LINK_POSITION, value)
            .apply()

    fun beginRuntimeLink(linkId: Long, position: Int, url: String, engine: String) {
        if (runtimeCurrentLinkId != linkId) {
            preferences.edit()
                .putLong(KEY_RUNTIME_CURRENT_LINK_ID, linkId)
                .putInt(KEY_RUNTIME_CURRENT_LINK_POSITION, position)
                .putString(KEY_RUNTIME_CURRENT_LINK_URL, url.take(512))
                .putLong(KEY_RUNTIME_LINK_STARTED_AT, System.currentTimeMillis())
                .putString(KEY_RUNTIME_LINK_PHASE, LinkRuntimePhase.OPENING.name)
                .remove(KEY_RUNTIME_ACTION_EXECUTED)
                .putInt(KEY_RUNTIME_ACTION_ATTEMPT_COUNT, 0)
                .putInt(KEY_RUNTIME_RECOVERY_REOPEN_ATTEMPTS, 0)
                .putString(KEY_RUNTIME_LAST_ENGINE_STATE, engine.take(120))
                .apply()
        } else {
            runtimeLastEngineState = engine
        }
    }

    fun markRuntimePhase(phase: LinkRuntimePhase, engineState: String? = null) {
        preferences.edit().apply {
            putString(KEY_RUNTIME_LINK_PHASE, phase.name)
            if (!engineState.isNullOrBlank()) {
                putString(KEY_RUNTIME_LAST_ENGINE_STATE, engineState.take(120))
            }
        }.apply()
    }

    fun recordRuntimeAction(action: String, engine: String) {
        preferences.edit()
            .putString(KEY_RUNTIME_ACTION_EXECUTED, action.take(80))
            .putInt(KEY_RUNTIME_ACTION_ATTEMPT_COUNT, runtimeActionAttemptCount + 1)
            .putString(KEY_RUNTIME_LINK_PHASE, LinkRuntimePhase.ACTION_TAPPED.name)
            .putString(KEY_RUNTIME_LAST_ENGINE_STATE, engine.take(120))
            .apply()
    }

    fun canReopenCurrentLinkOnce(linkId: Long): Boolean =
        runtimeCurrentLinkId == linkId &&
            runtimeRecoveryReopenAttempts <
                com.althmany.groupmanager.domain.SmartExitControllerPolicy.MAX_REOPEN_ATTEMPTS_PER_LINK

    fun recordRecoveryReopen(linkId: Long): Boolean {
        if (!canReopenCurrentLinkOnce(linkId)) return false
        runtimeRecoveryReopenAttempts += 1
        markRuntimePhase(LinkRuntimePhase.OPENING, "RECOVERY_REOPEN")
        return true
    }

    fun buildRuntimeAuditDetail(
        detail: String,
        resultCodeName: String,
        backend: String
    ): String {
        val elapsed = if (runtimeLinkStartedAt > 0L) {
            (System.currentTimeMillis() - runtimeLinkStartedAt).coerceAtLeast(0L)
        } else 0L
        val resultClass = SmartResultClassifier.fromResultCode(resultCodeName)
        return buildString {
            append(detail.take(900))
            append(" | resultClass=").append(resultClass.name)
            append(" | attempts=").append(runtimeActionAttemptCount)
            append(" | elapsedMs=").append(elapsed)
            append(" | backend=").append(backend)
            append(" | package=").append(runtimeLockedWhatsAppPackage.orEmpty())
            append(" | userId=").append(runtimeLockedAndroidUserId)
            append(" | profile=").append(runtimeLockedProfileKey.orEmpty())
            append(" | phase=").append(runtimeLinkPhase.name)
        }.take(1_600)
    }

    fun finishRuntimeLink(position: Int, engineState: String) {
        preferences.edit()
            .putInt(KEY_LAST_COMPLETED_LINK_POSITION, position)
            .putString(KEY_RUNTIME_LINK_PHASE, LinkRuntimePhase.ADVANCING.name)
            .putString(KEY_RUNTIME_LAST_ENGINE_STATE, engineState.take(120))
            .apply()
    }

    /**
     * User opt-in: if Android recreates the Accessibility service during the current explicit run,
     * preserve the saved run state and continue from the same link when the service reconnects.
     * This never extends the explicit-run window or bypasses a restriction stop.
     */
    var autoResumeCurrentRun: Boolean
        get() = preferences.getBoolean(KEY_AUTO_RESUME_CURRENT_RUN, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_RESUME_CURRENT_RUN, value).apply()

    /** Pause the current run when the foreground app leaves the selected WhatsApp target. */
    var autoPauseOutsideWhatsApp: Boolean
        get() = preferences.getBoolean(KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP, value).apply()

    /** Internal marker so automatic resume never overrides a manual pause. */
    var pausedBecauseOutsideTarget: Boolean
        get() = preferences.getBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, false)
        set(value) = preferences.edit().putBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, value).apply()

    /** Return to the AL-thmany dashboard when the explicit run completes. */
    var returnToAppOnRunComplete: Boolean
        get() = preferences.getBoolean(KEY_RETURN_TO_APP_ON_COMPLETE, true)
        set(value) = preferences.edit().putBoolean(KEY_RETURN_TO_APP_ON_COMPLETE, value).apply()

    /** Exact package selected from the installed WhatsApp-app picker. Null keeps legacy Auto mode. */
    var selectedWhatsAppPackage: String?
        get() = preferences.getString(KEY_SELECTED_WHATSAPP_PACKAGE, null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_SELECTED_WHATSAPP_PACKAGE)
            else putString(KEY_SELECTED_WHATSAPP_PACKAGE, value)
        }.apply()

    var selectedWhatsAppLabel: String?
        get() = preferences.getString(KEY_SELECTED_WHATSAPP_LABEL, null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_SELECTED_WHATSAPP_LABEL)
            else putString(KEY_SELECTED_WHATSAPP_LABEL, value)
        }.apply()

    /** Never fall back to a resolver/browser when a profile-local WhatsApp target is expected. */

    /**
     * Remote Secure Folder target.
     *
     * Al-othmany stays in the host Android user where Shizuku is available.
     * The Shizuku engine may target one explicitly selected secondary/Knox user.
     * The target is never guessed during automation and is re-verified by the service.
     */
    var remoteSecureFolderEnabled: Boolean
        get() = preferences.getBoolean(KEY_REMOTE_SECURE_ENABLED, false)
        private set(value) = preferences.edit().putBoolean(KEY_REMOTE_SECURE_ENABLED, value).apply()

    var remoteSecureAndroidUserId: Int
        get() = preferences.getInt(KEY_REMOTE_SECURE_USER_ID, -1)
        private set(value) = preferences.edit().putInt(KEY_REMOTE_SECURE_USER_ID, value).apply()

    var remoteSecureWhatsAppPackage: String?
        get() = preferences.getString(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE, null)
        private set(value) = preferences.edit()
            .apply {
                if (value.isNullOrBlank()) remove(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE)
                else putString(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE, value)
            }
            .apply()

    var remoteSecureUserLabel: String?
        get() = preferences.getString(KEY_REMOTE_SECURE_USER_LABEL, null)
        private set(value) = preferences.edit()
            .apply {
                if (value.isNullOrBlank()) remove(KEY_REMOTE_SECURE_USER_LABEL)
                else putString(KEY_REMOTE_SECURE_USER_LABEL, value.take(80))
            }
            .apply()

    fun setRemoteSecureTarget(userId: Int, packageName: String, label: String?) {
        require(userId >= 0) { "Remote Secure user id must be non-negative." }
        require(packageName in setOf("com.whatsapp", "com.whatsapp.w4b", "com.whatsapp2")) {
            "Unsupported Remote Secure WhatsApp package."
        }
        preferences.edit()
            .putBoolean(KEY_REMOTE_SECURE_ENABLED, true)
            .putInt(KEY_REMOTE_SECURE_USER_ID, userId)
            .putString(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE, packageName)
            .putString(KEY_REMOTE_SECURE_USER_LABEL, label?.take(80).orEmpty())
            .remove(KEY_RUNTIME_LOCKED_ANDROID_USER_ID)
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .apply()
    }

    fun clearRemoteSecureTarget() {
        preferences.edit()
            .putBoolean(KEY_REMOTE_SECURE_ENABLED, false)
            .remove(KEY_REMOTE_SECURE_USER_ID)
            .remove(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE)
            .remove(KEY_REMOTE_SECURE_USER_LABEL)
            .remove(KEY_RUNTIME_LOCKED_ANDROID_USER_ID)
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .apply()
    }

    fun hasValidRemoteSecureTarget(): Boolean =
        remoteSecureFolderEnabled &&
            remoteSecureAndroidUserId >= 0 &&
            remoteSecureWhatsAppPackage in setOf("com.whatsapp", "com.whatsapp.w4b", "com.whatsapp2")

    var strictProfileTargeting: Boolean
        get() = preferences.getBoolean(KEY_STRICT_PROFILE_TARGETING, true)
        set(value) = preferences.edit().putBoolean(KEY_STRICT_PROFILE_TARGETING, value).apply()

    /** Package/profile pinned for the current explicit run. Accessibility ignores other WhatsApps. */
    var runtimeLockedWhatsAppPackage: String?
        get() = preferences.getString(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE, null)?.takeIf { it.isNotBlank() }
        private set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            else putString(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE, value)
        }.apply()

    var runtimeLockedProfileKey: String?
        get() = preferences.getString(KEY_RUNTIME_LOCKED_PROFILE_KEY, null)?.takeIf { it.isNotBlank() }
        private set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            else putString(KEY_RUNTIME_LOCKED_PROFILE_KEY, value)
        }.apply()

    fun lockRuntimeTarget(packageName: String, profileKey: String) {
        preferences.edit()
            .putString(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE, packageName)
            .putString(KEY_RUNTIME_LOCKED_PROFILE_KEY, profileKey)
            .remove(KEY_RUNTIME_LOCKED_ANDROID_USER_ID)
            .apply()
    }

    fun clearRuntimeTargetLock() {
        preferences.edit()
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .remove(KEY_RUNTIME_LOCKED_ANDROID_USER_ID)
            .apply()
    }

    /** Join a community, then traverse only semantically identified subgroup rows. */
    var communityTraversalEnabled: Boolean
        get() = preferences.getBoolean(KEY_COMMUNITY_TRAVERSAL_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_COMMUNITY_TRAVERSAL_ENABLED, value).apply()

    val communityTraversalActive: Boolean
        get() = communityTraversalParentLinkId > 0L && communityTraversalStage != CommunityTraversalStage.INACTIVE

    var communityTraversalParentLinkId: Long
        get() = preferences.getLong(KEY_COMMUNITY_PARENT_LINK_ID, -1L)
        private set(value) = preferences.edit().putLong(KEY_COMMUNITY_PARENT_LINK_ID, value).apply()

    var communityTraversalStage: CommunityTraversalStage
        get() = enumValueOrDefault(preferences.getString(KEY_COMMUNITY_STAGE, null), CommunityTraversalStage.INACTIVE)
        private set(value) = preferences.edit().putString(KEY_COMMUNITY_STAGE, value.name).apply()

    var communityScrollAttempts: Int
        get() = preferences.getInt(KEY_COMMUNITY_SCROLL_ATTEMPTS, 0).coerceAtLeast(0)
        set(value) = preferences.edit().putInt(KEY_COMMUNITY_SCROLL_ATTEMPTS, value.coerceAtLeast(0)).apply()

    var communityProcessedGroupCount: Int
        get() = preferences.getInt(KEY_COMMUNITY_PROCESSED_GROUP_COUNT, 0).coerceAtLeast(0)
        private set(value) = preferences.edit().putInt(KEY_COMMUNITY_PROCESSED_GROUP_COUNT, value.coerceAtLeast(0)).apply()

    var communityCurrentGroupKey: String?
        get() = preferences.getString(KEY_COMMUNITY_CURRENT_GROUP_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_COMMUNITY_CURRENT_GROUP_KEY)
            else putString(KEY_COMMUNITY_CURRENT_GROUP_KEY, value.take(240))
        }.apply()

    val communityProcessedGroupKeys: Set<String>
        get() = preferences.getStringSet(KEY_COMMUNITY_PROCESSED_GROUP_KEYS, emptySet())?.toSet().orEmpty()

    fun beginCommunityTraversal(parentLinkId: Long) {
        preferences.edit()
            .putLong(KEY_COMMUNITY_PARENT_LINK_ID, parentLinkId)
            .putString(KEY_COMMUNITY_STAGE, CommunityTraversalStage.ENTERING_COMMUNITY.name)
            .putInt(KEY_COMMUNITY_SCROLL_ATTEMPTS, 0)
            .putInt(KEY_COMMUNITY_PROCESSED_GROUP_COUNT, 0)
            .putStringSet(KEY_COMMUNITY_PROCESSED_GROUP_KEYS, emptySet())
            .remove(KEY_COMMUNITY_CURRENT_GROUP_KEY)
            .apply()
    }

    fun transitionCommunityTraversal(stage: CommunityTraversalStage) {
        communityTraversalStage = stage
    }

    fun markCommunityGroupProcessed(key: String?) {
        val safeKey = key?.takeIf { it.isNotBlank() }?.take(240)
        if (safeKey == null) {
            communityCurrentGroupKey = null
            return
        }
        val current = communityProcessedGroupKeys
        if (safeKey in current) {
            communityCurrentGroupKey = null
            return
        }
        val next = current.toMutableSet().apply { add(safeKey) }
        preferences.edit()
            .putStringSet(KEY_COMMUNITY_PROCESSED_GROUP_KEYS, next)
            .putInt(KEY_COMMUNITY_PROCESSED_GROUP_COUNT, communityProcessedGroupCount + 1)
            .remove(KEY_COMMUNITY_CURRENT_GROUP_KEY)
            .apply()
    }

    fun clearCommunityTraversal() {
        preferences.edit()
            .remove(KEY_COMMUNITY_PARENT_LINK_ID)
            .putString(KEY_COMMUNITY_STAGE, CommunityTraversalStage.INACTIVE.name)
            .remove(KEY_COMMUNITY_SCROLL_ATTEMPTS)
            .remove(KEY_COMMUNITY_PROCESSED_GROUP_COUNT)
            .remove(KEY_COMMUNITY_PROCESSED_GROUP_KEYS)
            .remove(KEY_COMMUNITY_CURRENT_GROUP_KEY)
            .apply()
    }

    /** Developer-safe observation mode: classify and log but never click WhatsApp controls. */
    var runtimeShadowMode: Boolean
        get() = preferences.getBoolean(KEY_RUNTIME_SHADOW_MODE, false)
        set(value) = preferences.edit().putBoolean(KEY_RUNTIME_SHADOW_MODE, value).apply()

    /**
     * One-time safety migration for installs that persisted the developer-only Shadow switch.
     * A normal upgraded install must execute Join/Request actions instead of silently observing.
     */
    fun applyExecutableRuntimeMigration() {
        if (preferences.getBoolean(KEY_EXECUTABLE_RUNTIME_MIGRATED, false)) return
        preferences.edit()
            .putBoolean(KEY_RUNTIME_SHADOW_MODE, false)
            .putBoolean(KEY_EXECUTABLE_RUNTIME_MIGRATED, true)
            .apply()
    }

    /** Rotating local runtime journal used for troubleshooting decisions and stalls. */
    var runtimeDiagnosticJournal: Boolean
        get() = preferences.getBoolean(KEY_RUNTIME_DIAGNOSTIC_JOURNAL, true)
        set(value) = preferences.edit().putBoolean(KEY_RUNTIME_DIAGNOSTIC_JOURNAL, value).apply()


    /** Planned local start handled by the enabled Accessibility service. */
    var scheduledStartAtMillis: Long
        get() = preferences.getLong(KEY_SCHEDULED_START_AT, 0L)
        private set(value) = preferences.edit().putLong(KEY_SCHEDULED_START_AT, value).apply()

    var scheduledSessionId: String?
        get() = preferences.getString(KEY_SCHEDULED_SESSION_ID, null)
        private set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_SCHEDULED_SESSION_ID)
                else putString(KEY_SCHEDULED_SESSION_ID, value)
            }.apply()
        }

    var startMode: Int
        get() = preferences.getInt(KEY_START_MODE, START_MODE_NOW).coerceIn(START_MODE_NOW, START_MODE_CLOCK)
        set(value) = preferences.edit().putInt(KEY_START_MODE, value.coerceIn(START_MODE_NOW, START_MODE_CLOCK)).apply()

    var startDelaySeconds: Int
        get() = preferences.getInt(KEY_START_DELAY_SECONDS, 5).coerceIn(1, 60)
        set(value) = preferences.edit().putInt(KEY_START_DELAY_SECONDS, value.coerceIn(1, 60)).apply()

    var scheduledClockHour: Int
        get() = preferences.getInt(KEY_SCHEDULED_CLOCK_HOUR, 20).coerceIn(0, 23)
        set(value) = preferences.edit().putInt(KEY_SCHEDULED_CLOCK_HOUR, value.coerceIn(0, 23)).apply()

    var scheduledClockMinute: Int
        get() = preferences.getInt(KEY_SCHEDULED_CLOCK_MINUTE, 0).coerceIn(0, 59)
        set(value) = preferences.edit().putInt(KEY_SCHEDULED_CLOCK_MINUTE, value.coerceIn(0, 59)).apply()

    var scheduledDateYear: Int
        get() = preferences.getInt(KEY_SCHEDULED_DATE_YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
        set(value) = preferences.edit().putInt(KEY_SCHEDULED_DATE_YEAR, value.coerceIn(2024, 2100)).apply()

    var scheduledDateMonth: Int
        get() = preferences.getInt(KEY_SCHEDULED_DATE_MONTH, java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)).coerceIn(0, 11)
        set(value) = preferences.edit().putInt(KEY_SCHEDULED_DATE_MONTH, value.coerceIn(0, 11)).apply()

    var scheduledDateDay: Int
        get() = preferences.getInt(KEY_SCHEDULED_DATE_DAY, java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)).coerceIn(1, 31)
        set(value) = preferences.edit().putInt(KEY_SCHEDULED_DATE_DAY, value.coerceIn(1, 31)).apply()

    val hasScheduledStart: Boolean
        get() = scheduledSessionId != null && scheduledStartAtMillis > 0L

    fun scheduleAccessibilityBatch(sessionId: String, startAtMillis: Long) {
        val safeStart = startAtMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        preferences.edit()
            .putString(KEY_SCHEDULED_SESSION_ID, sessionId)
            .putLong(KEY_SCHEDULED_START_AT, safeStart)
            .putString(KEY_ACCESSIBILITY_SESSION_ID, sessionId)
            .putBoolean(KEY_ACCESSIBILITY_BATCH_RUNNING, false)
            .putBoolean(KEY_ACCESSIBILITY_PAUSED, false)
            .putBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, false)
            .putInt(KEY_ACCESSIBILITY_PROCESSED_COUNT, 0)
            .putString(KEY_AUTOMATION_STAGE, AutomationStage.SCHEDULED.name)
            .putString(KEY_AUTOMATION_STOP_REASON, AutomationStopReason.NONE.name)
            .putString(KEY_AUTOMATION_DIAGNOSTIC, "Automatic start scheduled")
            .putLong(KEY_AUTOMATION_STAGE_STARTED_AT, System.currentTimeMillis())
            .putInt(KEY_AUTOMATION_RETRY_COUNT, 0)
            .apply()
    }

    fun clearScheduledStart() {
        preferences.edit()
            .remove(KEY_SCHEDULED_SESSION_ID)
            .remove(KEY_SCHEDULED_START_AT)
            .apply()
    }

    /** User-controlled opt-in. The Android system permission is enabled separately. */
    var accessibilityQuickJoin: Boolean
        get() = preferences.getBoolean(KEY_ACCESSIBILITY_QUICK_JOIN, false)
        set(value) {
            preferences.edit().apply {
                putBoolean(KEY_ACCESSIBILITY_QUICK_JOIN, value)
                if (!value) {
                    putBoolean(KEY_ACCESSIBILITY_BATCH_RUNNING, false)
                    remove(KEY_ACCESSIBILITY_PENDING_LINK_ID)
                    remove(KEY_ACCESSIBILITY_PENDING_ACTION)
                    remove(KEY_ACCESSIBILITY_PENDING_TARGET)
                    remove(KEY_ACCESSIBILITY_PENDING_AT)
                    putBoolean(KEY_ACCESSIBILITY_PAUSED, false)
                    putString(KEY_AUTOMATION_STAGE, AutomationStage.STOPPED.name)
                    putString(KEY_AUTOMATION_STOP_REASON, AutomationStopReason.SERVICE_DISABLED.name)
                    putString(KEY_AUTOMATION_DIAGNOSTIC, "Accessibility automation disabled")
                    putLong(KEY_AUTOMATION_STAGE_STARTED_AT, System.currentTimeMillis())
                    putInt(KEY_AUTOMATION_RETRY_COUNT, 0)
                }
            }.apply()
        }

    /** True only after the user explicitly starts the current batch from the app. */
    var accessibilityBatchRunning: Boolean
        get() = preferences.getBoolean(KEY_ACCESSIBILITY_BATCH_RUNNING, false)
        private set(value) = preferences.edit()
            .putBoolean(KEY_ACCESSIBILITY_BATCH_RUNNING, value)
            .apply()

    var accessibilityProcessedCount: Int
        get() = preferences.getInt(KEY_ACCESSIBILITY_PROCESSED_COUNT, 0)
        set(value) = preferences.edit()
            .putInt(
                KEY_ACCESSIBILITY_PROCESSED_COUNT,
                value.coerceIn(0, AutomationPolicy.BATCH_SIZE)
            )
            .apply()

    var accessibilityJoinDelaySeconds: Int
        get() = AutomationPolicy.notificationDelaySeconds(interLinkDelayMs)
        set(value) {
            interLinkDelayMs = AutomationPolicy.clampDelaySeconds(value) * 1_000
        }

    /** One speed value shared by Accessibility and Shizuku, with 100 ms UI precision. */
    var interLinkDelayMs: Int
        get() {
            val legacyMs = AutomationPolicy.clampDelaySeconds(
                preferences.getInt(
                    KEY_ACCESSIBILITY_JOIN_DELAY_SECONDS,
                    AutomationPolicy.DEFAULT_DELAY_SECONDS
                )
            ) * 1_000
            return AutomationPolicy.clampInterLinkDelayMs(
                preferences.getInt(KEY_INTER_LINK_DELAY_MS, legacyMs)
            )
        }
        set(value) {
            val safe = AutomationPolicy.clampInterLinkDelayMs(value)
            preferences.edit()
                .putInt(KEY_INTER_LINK_DELAY_MS, safe)
                .putInt(
                    KEY_ACCESSIBILITY_JOIN_DELAY_SECONDS,
                    AutomationPolicy.notificationDelaySeconds(safe)
                )
                .apply()
        }

    var accessibilityActionTimeoutSeconds: Int
        get() = AutomationPolicy.clampActionTimeoutSeconds(
            preferences.getInt(
                KEY_ACCESSIBILITY_ACTION_TIMEOUT_SECONDS,
                AutomationPolicy.DEFAULT_ACTION_TIMEOUT_SECONDS
            )
        )
        set(value) = preferences.edit()
            .putInt(
                KEY_ACCESSIBILITY_ACTION_TIMEOUT_SECONDS,
                AutomationPolicy.clampActionTimeoutSeconds(value)
            )
            .apply()

    var accessibilityPaused: Boolean
        get() = preferences.getBoolean(KEY_ACCESSIBILITY_PAUSED, false)
        private set(value) = preferences.edit().putBoolean(KEY_ACCESSIBILITY_PAUSED, value).apply()

    var automationStage: AutomationStage
        get() = enumValueOrDefault(
            preferences.getString(KEY_AUTOMATION_STAGE, null),
            AutomationStage.IDLE
        )
        private set(value) = preferences.edit().putString(KEY_AUTOMATION_STAGE, value.name).apply()

    var automationStopReason: AutomationStopReason
        get() = enumValueOrDefault(
            preferences.getString(KEY_AUTOMATION_STOP_REASON, null),
            AutomationStopReason.NONE
        )
        private set(value) = preferences.edit()
            .putString(KEY_AUTOMATION_STOP_REASON, value.name)
            .apply()

    var automationDiagnostic: String
        get() = preferences.getString(KEY_AUTOMATION_DIAGNOSTIC, "").orEmpty()
        private set(value) = preferences.edit()
            .putString(KEY_AUTOMATION_DIAGNOSTIC, value.take(240))
            .apply()

    var automationStageStartedAt: Long
        get() = preferences.getLong(KEY_AUTOMATION_STAGE_STARTED_AT, 0L)
        private set(value) = preferences.edit()
            .putLong(KEY_AUTOMATION_STAGE_STARTED_AT, value)
            .apply()

    var automationRetryCount: Int
        get() = preferences.getInt(KEY_AUTOMATION_RETRY_COUNT, 0)
        private set(value) = preferences.edit()
            .putInt(KEY_AUTOMATION_RETRY_COUNT, value.coerceIn(0, 10))
            .apply()

    var accessibilitySessionId: String?
        get() = preferences.getString(KEY_ACCESSIBILITY_SESSION_ID, null)
        private set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_ACCESSIBILITY_SESSION_ID)
                else putString(KEY_ACCESSIBILITY_SESSION_ID, value)
            }.apply()
        }

    var accessibilityPendingLinkId: Long
        get() = preferences.getLong(KEY_ACCESSIBILITY_PENDING_LINK_ID, -1L)
        set(value) = preferences.edit().putLong(KEY_ACCESSIBILITY_PENDING_LINK_ID, value).apply()

    var accessibilityPendingAction: String?
        get() = preferences.getString(KEY_ACCESSIBILITY_PENDING_ACTION, null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_ACCESSIBILITY_PENDING_ACTION)
                else putString(KEY_ACCESSIBILITY_PENDING_ACTION, value)
            }.apply()
        }

    var accessibilityPendingTarget: AccessibilityInviteTarget
        get() = enumValueOrDefault(
            preferences.getString(KEY_ACCESSIBILITY_PENDING_TARGET, null),
            AccessibilityInviteTarget.UNKNOWN
        )
        set(value) = preferences.edit().putString(KEY_ACCESSIBILITY_PENDING_TARGET, value.name).apply()

    var accessibilityPendingAt: Long
        get() = preferences.getLong(KEY_ACCESSIBILITY_PENDING_AT, 0L)
        set(value) = preferences.edit().putLong(KEY_ACCESSIBILITY_PENDING_AT, value).apply()

    fun setAccessibilityPending(
        linkId: Long,
        action: String,
        target: AccessibilityInviteTarget = AccessibilityInviteTarget.UNKNOWN
    ) {
        preferences.edit()
            .putLong(KEY_ACCESSIBILITY_PENDING_LINK_ID, linkId)
            .putString(KEY_ACCESSIBILITY_PENDING_ACTION, action)
            .putString(KEY_ACCESSIBILITY_PENDING_TARGET, target.name)
            .putLong(KEY_ACCESSIBILITY_PENDING_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearAccessibilityPending() {
        preferences.edit()
            .remove(KEY_ACCESSIBILITY_PENDING_LINK_ID)
            .remove(KEY_ACCESSIBILITY_PENDING_ACTION)
            .remove(KEY_ACCESSIBILITY_PENDING_TARGET)
            .remove(KEY_ACCESSIBILITY_PENDING_AT)
            .apply()
    }

    fun startAccessibilityBatch(sessionId: String) {
        preferences.edit()
            .putString(KEY_ACCESSIBILITY_SESSION_ID, sessionId)
            // A real user-started run must always execute actions. Shadow is a developer-only
            // observer and a stale persisted toggle must never silently suppress Join.
            .putBoolean(KEY_RUNTIME_SHADOW_MODE, false)
            .remove(KEY_SCHEDULED_SESSION_ID)
            .remove(KEY_SCHEDULED_START_AT)
            .putBoolean(KEY_ACCESSIBILITY_BATCH_RUNNING, true)
            .putBoolean(KEY_ACCESSIBILITY_PAUSED, false)
            .putBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, false)
            .putInt(KEY_ACCESSIBILITY_PROCESSED_COUNT, 0)
            .putString(KEY_AUTOMATION_STAGE, AutomationStage.OPENING_LINK.name)
            .putString(KEY_AUTOMATION_STOP_REASON, AutomationStopReason.NONE.name)
            .putString(KEY_AUTOMATION_DIAGNOSTIC, "Preparing the first link")
            .putLong(KEY_AUTOMATION_STAGE_STARTED_AT, System.currentTimeMillis())
            .putInt(KEY_AUTOMATION_RETRY_COUNT, 0)
            .remove(KEY_ACCESSIBILITY_PENDING_LINK_ID)
            .remove(KEY_ACCESSIBILITY_PENDING_ACTION)
            .remove(KEY_ACCESSIBILITY_PENDING_TARGET)
            .remove(KEY_ACCESSIBILITY_PENDING_AT)
            .remove(KEY_COMMUNITY_PARENT_LINK_ID)
            .putString(KEY_COMMUNITY_STAGE, CommunityTraversalStage.INACTIVE.name)
            .remove(KEY_COMMUNITY_SCROLL_ATTEMPTS)
            .remove(KEY_COMMUNITY_PROCESSED_GROUP_COUNT)
            .remove(KEY_COMMUNITY_PROCESSED_GROUP_KEYS)
            .remove(KEY_COMMUNITY_CURRENT_GROUP_KEY)
            .apply()
    }

    fun transitionAutomation(
        stage: AutomationStage,
        diagnostic: String,
        resetRetries: Boolean = false
    ) {
        preferences.edit().apply {
            putString(KEY_AUTOMATION_STAGE, stage.name)
            putString(KEY_AUTOMATION_DIAGNOSTIC, diagnostic.take(240))
            putLong(KEY_AUTOMATION_STAGE_STARTED_AT, System.currentTimeMillis())
            if (resetRetries) putInt(KEY_AUTOMATION_RETRY_COUNT, 0)
        }.apply()
    }

    fun incrementAutomationRetry(): Int {
        val next = (automationRetryCount + 1).coerceAtMost(10)
        automationRetryCount = next
        automationStageStartedAt = System.currentTimeMillis()
        return next
    }

    fun markAutomationLaunched() {
        accessibilityPaused = false
        transitionAutomation(
            AutomationStage.WAITING_FOR_WHATSAPP,
            "Waiting for WhatsApp invitation screen",
            resetRetries = true
        )
    }

    fun pauseAccessibilityBatch(
        diagnostic: String = "Paused by user",
        outsideTarget: Boolean = false
    ) {
        if (!accessibilityBatchRunning) return
        accessibilityPaused = true
        pausedBecauseOutsideTarget = outsideTarget
        transitionAutomation(AutomationStage.PAUSED, diagnostic)
    }

    fun resumeAccessibilityBatch(diagnostic: String = "Resumed by user") {
        if (!accessibilityBatchRunning) return
        accessibilityPaused = false
        pausedBecauseOutsideTarget = false
        transitionAutomation(
            AutomationStage.LOOKING_FOR_PREVIEW,
            diagnostic,
            resetRetries = true
        )
    }

    fun stopAccessibilityBatch(
        reason: AutomationStopReason = AutomationStopReason.USER_STOPPED,
        diagnostic: String = "Batch stopped"
    ) {
        preferences.edit()
            .putBoolean(KEY_ACCESSIBILITY_BATCH_RUNNING, false)
            .putBoolean(KEY_ACCESSIBILITY_PAUSED, false)
            .putString(KEY_AUTOMATION_STAGE, AutomationStage.STOPPED.name)
            .putString(KEY_AUTOMATION_STOP_REASON, reason.name)
            .putString(KEY_AUTOMATION_DIAGNOSTIC, diagnostic.take(240))
            .putLong(KEY_AUTOMATION_STAGE_STARTED_AT, System.currentTimeMillis())
            .putInt(KEY_AUTOMATION_RETRY_COUNT, 0)
            .remove(KEY_ACCESSIBILITY_PENDING_LINK_ID)
            .remove(KEY_ACCESSIBILITY_PENDING_ACTION)
            .remove(KEY_ACCESSIBILITY_PENDING_TARGET)
            .remove(KEY_ACCESSIBILITY_PENDING_AT)
            .remove(KEY_SCHEDULED_SESSION_ID)
            .remove(KEY_SCHEDULED_START_AT)
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .remove(KEY_COMMUNITY_PARENT_LINK_ID)
            .putString(KEY_COMMUNITY_STAGE, CommunityTraversalStage.INACTIVE.name)
            .remove(KEY_COMMUNITY_SCROLL_ATTEMPTS)
            .remove(KEY_COMMUNITY_PROCESSED_GROUP_COUNT)
            .remove(KEY_COMMUNITY_PROCESSED_GROUP_KEYS)
            .remove(KEY_COMMUNITY_CURRENT_GROUP_KEY)
            .apply()
    }

    fun completeAccessibilityBatch(
        reason: AutomationStopReason,
        diagnostic: String
    ) {
        preferences.edit()
            .putBoolean(KEY_ACCESSIBILITY_BATCH_RUNNING, false)
            .putBoolean(KEY_ACCESSIBILITY_PAUSED, false)
            .putString(KEY_AUTOMATION_STAGE, AutomationStage.COMPLETED.name)
            .putString(KEY_AUTOMATION_STOP_REASON, reason.name)
            .putString(KEY_AUTOMATION_DIAGNOSTIC, diagnostic.take(240))
            .putLong(KEY_AUTOMATION_STAGE_STARTED_AT, System.currentTimeMillis())
            .putInt(KEY_AUTOMATION_RETRY_COUNT, 0)
            .remove(KEY_ACCESSIBILITY_PENDING_LINK_ID)
            .remove(KEY_ACCESSIBILITY_PENDING_ACTION)
            .remove(KEY_ACCESSIBILITY_PENDING_TARGET)
            .remove(KEY_ACCESSIBILITY_PENDING_AT)
            .remove(KEY_SCHEDULED_SESSION_ID)
            .remove(KEY_SCHEDULED_START_AT)
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .remove(KEY_COMMUNITY_PARENT_LINK_ID)
            .putString(KEY_COMMUNITY_STAGE, CommunityTraversalStage.INACTIVE.name)
            .remove(KEY_COMMUNITY_SCROLL_ATTEMPTS)
            .remove(KEY_COMMUNITY_PROCESSED_GROUP_COUNT)
            .remove(KEY_COMMUNITY_PROCESSED_GROUP_KEYS)
            .remove(KEY_COMMUNITY_CURRENT_GROUP_KEY)
            .apply()
    }

    fun resetAccessibilityRun(sessionId: String?) {
        preferences.edit().apply {
            if (sessionId == null) remove(KEY_ACCESSIBILITY_SESSION_ID)
            else putString(KEY_ACCESSIBILITY_SESSION_ID, sessionId)
            putBoolean(KEY_ACCESSIBILITY_BATCH_RUNNING, false)
            putBoolean(KEY_ACCESSIBILITY_PAUSED, false)
            putBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, false)
            putInt(KEY_ACCESSIBILITY_PROCESSED_COUNT, 0)
            putString(KEY_AUTOMATION_STAGE, AutomationStage.IDLE.name)
            putString(KEY_AUTOMATION_STOP_REASON, AutomationStopReason.NONE.name)
            putString(KEY_AUTOMATION_DIAGNOSTIC, "")
            putLong(KEY_AUTOMATION_STAGE_STARTED_AT, 0L)
            putInt(KEY_AUTOMATION_RETRY_COUNT, 0)
            remove(KEY_ACCESSIBILITY_PENDING_LINK_ID)
            remove(KEY_ACCESSIBILITY_PENDING_ACTION)
            remove(KEY_ACCESSIBILITY_PENDING_TARGET)
            remove(KEY_ACCESSIBILITY_PENDING_AT)
            remove(KEY_SCHEDULED_SESSION_ID)
            remove(KEY_SCHEDULED_START_AT)
            remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            remove(KEY_COMMUNITY_PARENT_LINK_ID)
            putString(KEY_COMMUNITY_STAGE, CommunityTraversalStage.INACTIVE.name)
            remove(KEY_COMMUNITY_SCROLL_ATTEMPTS)
            remove(KEY_COMMUNITY_PROCESSED_GROUP_COUNT)
            remove(KEY_COMMUNITY_PROCESSED_GROUP_KEYS)
            remove(KEY_COMMUNITY_CURRENT_GROUP_KEY)
        }.apply()
    }

    var automationBackend: AutomationBackend
        get() = enumValueOrDefault(
            preferences.getString(KEY_AUTOMATION_BACKEND, null),
            AutomationBackend.AUTO
        )
        set(value) = preferences.edit().putString(KEY_AUTOMATION_BACKEND, value.name).apply()

    /** Concrete engine chosen for the currently explicit run. AUTO is never executed directly. */
    var runtimeAutomationBackend: AutomationBackend
        get() = enumValueOrDefault(
            preferences.getString(KEY_RUNTIME_AUTOMATION_BACKEND, null),
            AutomationBackend.ACCESSIBILITY
        )
        set(value) = preferences.edit().putString(KEY_RUNTIME_AUTOMATION_BACKEND, value.name).apply()

    var preferredTarget: PreferredTarget
        get() = enumValueOrDefault(
            preferences.getString(KEY_PREFERRED_TARGET, null),
            PreferredTarget.AUTO
        )
        set(value) = preferences.edit().putString(KEY_PREFERRED_TARGET, value.name).apply()

    var themeMode: ThemeMode
        get() = enumValueOrDefault(
            preferences.getString(KEY_THEME_MODE, null),
            ThemeMode.SYSTEM
        )
        set(value) = preferences.edit().putString(KEY_THEME_MODE, value.name).apply()

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)

    companion object {
        private const val PREFS_NAME = "group_link_manager_preferences_v4"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val KEY_AUTO_ADVANCE = "auto_advance"
        private const val KEY_QUICK_JOIN_NOTIFICATION = "quick_join_notification"
        private const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"
        private const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        const val START_MODE_NOW = 0
        const val START_MODE_DELAY = 1
        const val START_MODE_CLOCK = 2

        private const val KEY_SMART_AUTO_START = "smart_auto_start"
        private const val KEY_FAST_HANDS_FREE_MODE = "fast_hands_free_mode"
        private const val KEY_RUNTIME_SPEED_MODE = "runtime_speed_mode"
        private const val KEY_CUSTOM_SCAN_MS = "custom_scan_ms"
        private const val KEY_CUSTOM_POST_TAP_MS = "custom_post_tap_ms"
        private const val KEY_CUSTOM_INTER_LINK_MS = "custom_inter_link_ms"
        private const val KEY_RESTRICTION_HANDLING_MODE = "restriction_handling_mode"
        private const val KEY_RUNTIME_LOCKED_ANDROID_USER_ID = "runtime_locked_android_user_id"
        private const val KEY_RUNTIME_LINK_PHASE = "runtime_link_phase"
        private const val KEY_RUNTIME_CURRENT_LINK_ID = "runtime_current_link_id"
        private const val KEY_RUNTIME_CURRENT_LINK_POSITION = "runtime_current_link_position"
        private const val KEY_RUNTIME_CURRENT_LINK_URL = "runtime_current_link_url"
        private const val KEY_RUNTIME_LINK_STARTED_AT = "runtime_link_started_at"
        private const val KEY_RUNTIME_ACTION_EXECUTED = "runtime_action_executed"
        private const val KEY_RUNTIME_ACTION_ATTEMPT_COUNT = "runtime_action_attempt_count"
        private const val KEY_RUNTIME_RECOVERY_REOPEN_ATTEMPTS = "runtime_recovery_reopen_attempts"
        private const val KEY_RUNTIME_LAST_ENGINE_STATE = "runtime_last_engine_state"
        private const val KEY_LAST_COMPLETED_LINK_POSITION = "last_completed_link_position"
        private const val KEY_AUTO_RESUME_CURRENT_RUN = "auto_resume_current_run"
        private const val KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP = "auto_pause_outside_whatsapp"
        private const val KEY_PAUSED_BECAUSE_OUTSIDE_TARGET = "paused_because_outside_target"
        private const val KEY_RETURN_TO_APP_ON_COMPLETE = "return_to_app_on_complete"
        private const val KEY_SELECTED_WHATSAPP_PACKAGE = "selected_whatsapp_package"
        private const val KEY_SELECTED_WHATSAPP_LABEL = "selected_whatsapp_label"
        private const val KEY_REMOTE_SECURE_ENABLED = "remote_secure_enabled"
        private const val KEY_REMOTE_SECURE_USER_ID = "remote_secure_user_id"
        private const val KEY_REMOTE_SECURE_WHATSAPP_PACKAGE = "remote_secure_whatsapp_package"
        private const val KEY_REMOTE_SECURE_USER_LABEL = "remote_secure_user_label"
        private const val KEY_STRICT_PROFILE_TARGETING = "strict_profile_targeting"
        private const val KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE = "runtime_locked_whatsapp_package"
        private const val KEY_RUNTIME_LOCKED_PROFILE_KEY = "runtime_locked_profile_key"
        private const val KEY_COMMUNITY_TRAVERSAL_ENABLED = "community_traversal_enabled"
        private const val KEY_COMMUNITY_PARENT_LINK_ID = "community_parent_link_id"
        private const val KEY_COMMUNITY_STAGE = "community_traversal_stage"
        private const val KEY_COMMUNITY_SCROLL_ATTEMPTS = "community_scroll_attempts"
        private const val KEY_COMMUNITY_PROCESSED_GROUP_COUNT = "community_processed_group_count"
        private const val KEY_COMMUNITY_PROCESSED_GROUP_KEYS = "community_processed_group_keys"
        private const val KEY_COMMUNITY_CURRENT_GROUP_KEY = "community_current_group_key"
        private const val KEY_RUNTIME_SHADOW_MODE = "runtime_shadow_mode"
        private const val KEY_EXECUTABLE_RUNTIME_MIGRATED = "executable_runtime_migrated_v274"
        private const val KEY_RUNTIME_DIAGNOSTIC_JOURNAL = "runtime_diagnostic_journal"
        private const val KEY_SCHEDULED_START_AT = "scheduled_start_at"
        private const val KEY_SCHEDULED_SESSION_ID = "scheduled_session_id"
        private const val KEY_START_MODE = "start_mode"
        private const val KEY_START_DELAY_SECONDS = "start_delay_seconds"
        private const val KEY_SCHEDULED_CLOCK_HOUR = "scheduled_clock_hour"
        private const val KEY_SCHEDULED_CLOCK_MINUTE = "scheduled_clock_minute"
        private const val KEY_SCHEDULED_DATE_YEAR = "scheduled_date_year"
        private const val KEY_SCHEDULED_DATE_MONTH = "scheduled_date_month"
        private const val KEY_SCHEDULED_DATE_DAY = "scheduled_date_day"
        private const val KEY_ACCESSIBILITY_QUICK_JOIN = "accessibility_quick_join"
        private const val KEY_ACCESSIBILITY_BATCH_RUNNING = "accessibility_batch_running"
        private const val KEY_ACCESSIBILITY_PROCESSED_COUNT = "accessibility_processed_count"
        private const val KEY_ACCESSIBILITY_JOIN_DELAY_SECONDS = "accessibility_join_delay_seconds"
        private const val KEY_INTER_LINK_DELAY_MS = "inter_link_delay_ms"
        private const val KEY_ACCESSIBILITY_ACTION_TIMEOUT_SECONDS = "accessibility_action_timeout_seconds"
        private const val KEY_ACCESSIBILITY_SESSION_ID = "accessibility_session_id"
        private const val KEY_ACCESSIBILITY_PENDING_LINK_ID = "accessibility_pending_link_id"
        private const val KEY_ACCESSIBILITY_PENDING_ACTION = "accessibility_pending_action"
        private const val KEY_ACCESSIBILITY_PENDING_TARGET = "accessibility_pending_target"
        private const val KEY_ACCESSIBILITY_PENDING_AT = "accessibility_pending_at"
        private const val KEY_ACCESSIBILITY_PAUSED = "accessibility_paused"
        private const val KEY_AUTOMATION_STAGE = "automation_stage"
        private const val KEY_AUTOMATION_STOP_REASON = "automation_stop_reason"
        private const val KEY_AUTOMATION_DIAGNOSTIC = "automation_diagnostic"
        private const val KEY_AUTOMATION_STAGE_STARTED_AT = "automation_stage_started_at"
        private const val KEY_AUTOMATION_RETRY_COUNT = "automation_retry_count"
        private const val KEY_AUTOMATION_BACKEND = "automation_backend"
        private const val KEY_RUNTIME_AUTOMATION_BACKEND = "runtime_automation_backend"
        private const val KEY_PREFERRED_TARGET = "preferred_target"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
