package com.althmany.groupmanager.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.althmany.groupmanager.BuildConfig
import com.althmany.groupmanager.GroupManagerApp
import com.althmany.groupmanager.R
import com.althmany.groupmanager.accessibility.AccessibilityStatus
import com.althmany.groupmanager.domain.AutomationPolicy
import com.althmany.groupmanager.databinding.ActivitySettingsBinding
import com.althmany.groupmanager.model.AutomationBackend
import com.althmany.groupmanager.domain.NativeEngineSetupAction
import com.althmany.groupmanager.model.PreferredTarget
import com.althmany.groupmanager.model.ThemeMode
import com.althmany.groupmanager.util.AccessibilitySettingsLauncher
import com.althmany.groupmanager.util.NativeProfileEngineRouter
import com.althmany.groupmanager.util.WorkProfileController
import com.althmany.groupmanager.util.ProfileAccessibilityRuntime
import com.althmany.groupmanager.util.ProfileAccessibilityPolicyInspector
import com.althmany.groupmanager.util.AccessibilityPolicyVisibility
import com.althmany.groupmanager.util.ProfileAccessibilityActivationState
import com.althmany.groupmanager.util.ProfileEnvironment
import com.althmany.groupmanager.util.QuickJoinNotification
import com.althmany.groupmanager.util.RuntimeDiagnosticStore
import com.althmany.groupmanager.util.WhatsAppLauncher
import com.althmany.groupmanager.util.nightMode
import com.althmany.groupmanager.shizuku.ShizukuBridge
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val app: GroupManagerApp get() = application as GroupManagerApp
    private var isBinding = false
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuBridge.PERMISSION_REQUEST_CODE) {
            runOnUiThread {
                renderShizukuState()
                Toast.makeText(
                    this,
                    if (grantResult == PackageManager.PERMISSION_GRANTED) R.string.shizuku_permission_requested
                    else R.string.shizuku_permission_denied,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3.5: full-screen dark/neon smart settings dashboard.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setGravity(Gravity.CENTER)
        window.setBackgroundDrawable(
            ColorDrawable(ContextCompat.getColor(this, R.color.sender_bg_deep))
        )
        binding.root.post {
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        bindCurrentSettings()
        configureListeners()
        renderInstalledApps()
        applyProfileTargetGuards()
        renderAccessibilityState()
        renderShizukuState()
        renderNativeProfileEngineState()
        binding.appVersionText.text = getString(
            R.string.settings_version_format,
            BuildConfig.VERSION_NAME
        )
    }

    override fun onResume() {
        super.onResume()
        applyProfileTargetGuards()
        renderAccessibilityState()
        renderShizukuState()
        renderNativeProfileEngineState()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun bindCurrentSettings() {
        isBinding = true
        binding.autoAdvanceSwitch.isChecked = app.preferences.autoAdvance
        binding.keepScreenAwakeSwitch.isChecked = app.preferences.keepScreenAwake
        binding.quickJoinNotificationSwitch.isChecked = app.preferences.quickJoinNotification
        binding.runtimeDiagnosticSwitch.isChecked = app.preferences.runtimeDiagnosticJournal
        binding.runtimeShadowSwitch.isChecked = app.preferences.runtimeShadowMode
        binding.accessibilityQuickJoinSwitch.isChecked = app.preferences.accessibilityQuickJoin
        binding.automationBackendRadioGroup.check(
            when (app.preferences.automationBackend) {
                AutomationBackend.AUTO -> R.id.backendAutoRadio
                AutomationBackend.ACCESSIBILITY -> R.id.backendAccessibilityRadio
                AutomationBackend.SHIZUKU -> R.id.backendShizukuRadio
            }
        )
        binding.joinDelayInput.setText(app.preferences.accessibilityJoinDelaySeconds.toString())
        binding.targetRadioGroup.check(
            when (app.preferences.preferredTarget) {
                PreferredTarget.AUTO -> R.id.targetAutoRadio
                PreferredTarget.PERSONAL -> R.id.targetPersonalRadio
                PreferredTarget.BUSINESS -> R.id.targetBusinessRadio
                PreferredTarget.CLONED -> R.id.targetCloneRadio
                PreferredTarget.BROWSER -> R.id.targetBrowserRadio
            }
        )
        binding.themeRadioGroup.check(
            when (app.preferences.themeMode) {
                ThemeMode.SYSTEM -> R.id.themeSystemRadio
                ThemeMode.LIGHT -> R.id.themeLightRadio
                ThemeMode.DARK -> R.id.themeDarkRadio
            }
        )
        isBinding = false
    }

    private fun configureListeners() {
        binding.autoAdvanceSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) app.preferences.autoAdvance = checked
        }

        binding.keepScreenAwakeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) app.preferences.keepScreenAwake = checked
        }

        binding.quickJoinNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) app.preferences.quickJoinNotification = checked
        }

        binding.runtimeDiagnosticSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) app.preferences.runtimeDiagnosticJournal = checked
        }

        binding.runtimeShadowSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) app.preferences.runtimeShadowMode = checked
        }

        binding.shareRuntimeDiagnosticsButton.setOnClickListener {
            val diagnostic = RuntimeDiagnosticStore.readRecent(this)
            if (diagnostic.isBlank()) {
                Toast.makeText(this, R.string.runtime_diagnostics_empty, Toast.LENGTH_SHORT).show()
            } else {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.runtime_diagnostics_subject))
                    putExtra(Intent.EXTRA_TEXT, diagnostic)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_runtime_diagnostics)))
            }
        }

        binding.clearRuntimeDiagnosticsButton.setOnClickListener {
            RuntimeDiagnosticStore.clear(this)
            Toast.makeText(this, R.string.runtime_diagnostics_cleared, Toast.LENGTH_SHORT).show()
        }

        binding.automationBackendRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isBinding) return@setOnCheckedChangeListener
            app.preferences.automationBackend = when (checkedId) {
                R.id.backendAccessibilityRadio -> AutomationBackend.ACCESSIBILITY
                R.id.backendShizukuRadio -> AutomationBackend.SHIZUKU
                else -> AutomationBackend.AUTO
            }
            renderShizukuState()
            renderNativeProfileEngineState()
        }

        binding.requestShizukuPermissionButton.setOnClickListener {
            val started = ShizukuBridge.requestPermission()
            Toast.makeText(
                this,
                if (started) R.string.shizuku_permission_requested else R.string.shizuku_start_first,
                Toast.LENGTH_SHORT
            ).show()
            renderShizukuState()
            renderNativeProfileEngineState()
        }

        binding.testShizukuButton.setOnClickListener {
            binding.testShizukuButton.isEnabled = false
            lifecycleScope.launch {
                val result = ShizukuBridge.probe(this@SettingsActivity, app.preferences.selectedWhatsAppPackage)
                binding.shizukuStatusText.text = getString(R.string.shizuku_probe_result, result)
                binding.testShizukuButton.isEnabled = true
                renderNativeProfileEngineState()
            }
        }

        binding.useRecommendedNativeEngineButton.setOnClickListener {
            val snapshot = NativeProfileEngineRouter.inspect(this, AutomationBackend.AUTO)
            app.preferences.automationBackend = AutomationBackend.AUTO
            when {
                snapshot.decision.backend == AutomationBackend.ACCESSIBILITY &&
                    snapshot.decision.setupAction == NativeEngineSetupAction.NONE -> {
                    Toast.makeText(this, R.string.native_engine_accessibility_selected, Toast.LENGTH_LONG).show()
                }
                snapshot.decision.backend == AutomationBackend.SHIZUKU &&
                    snapshot.decision.setupAction == NativeEngineSetupAction.NONE -> {
                    Toast.makeText(this, R.string.native_engine_shizuku_selected, Toast.LENGTH_LONG).show()
                }
                snapshot.decision.setupAction == NativeEngineSetupAction.APPLY_WORK_ACCESSIBILITY_POLICY -> {
                    Toast.makeText(this, R.string.native_engine_apply_work_policy, Toast.LENGTH_LONG).show()
                }
                snapshot.decision.setupAction == NativeEngineSetupAction.START_OR_AUTHORIZE_SHIZUKU -> {
                    Toast.makeText(this, R.string.native_engine_start_shizuku, Toast.LENGTH_LONG).show()
                }
                snapshot.decision.setupAction == NativeEngineSetupAction.ENABLE_LOCAL_ACCESSIBILITY -> {
                    Toast.makeText(this, R.string.native_engine_open_accessibility, Toast.LENGTH_LONG).show()
                    AccessibilitySettingsLauncher.openServiceDetails(this) || AccessibilitySettingsLauncher.openAccessibilityList(this)
                }
                else -> Toast.makeText(this, R.string.native_engine_blocked, Toast.LENGTH_LONG).show()
            }
            isBinding = true
            binding.automationBackendRadioGroup.check(R.id.backendAutoRadio)
            isBinding = false
            renderNativeProfileEngineState()
        }

        binding.applyWorkAccessibilityPolicyButton.setOnClickListener {
            val engine = NativeProfileEngineRouter.inspect(this, AutomationBackend.AUTO)
            if (!engine.workController.canManageAccessibilityPolicy && engine.shizukuReady) {
                // A normal app cannot replace an existing Work Profile owner. Make the control
                // useful by selecting the already-authorized exact-user Shizuku runtime instead;
                // the runtime still proves target package/user and semantic nodes before input.
                app.preferences.automationBackend = AutomationBackend.SHIZUKU
                isBinding = true
                binding.automationBackendRadioGroup.check(R.id.backendShizukuRadio)
                isBinding = false
                Toast.makeText(this, R.string.work_runtime_shizuku_selected, Toast.LENGTH_LONG).show()
            } else {
                val result = WorkProfileController.ensureSelfAccessibilityPermitted(this)
                val message = when {
                    result.success && result.changed -> R.string.work_policy_applied
                    result.success -> R.string.work_policy_already_allowed
                    result.messageCode == "EXTERNAL_PROFILE_OWNER" -> R.string.work_policy_not_owner
                    else -> R.string.work_policy_apply_failed
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            renderNativeProfileEngineState()
            renderShizukuState()
            renderAccessibilityState()
        }

        binding.accessibilityQuickJoinSwitch.setOnCheckedChangeListener { _, checked ->
            if (isBinding) return@setOnCheckedChangeListener

            if (!checked) {
                app.preferences.accessibilityQuickJoin = false
                app.preferences.stopAccessibilityBatch()
                QuickJoinNotification.cancel(this)
                renderAccessibilityState()
                return@setOnCheckedChangeListener
            }

            if (app.preferences.preferredTarget == PreferredTarget.BROWSER) {
                val profile = ProfileEnvironment.current(this)
                val discovered = WhatsAppLauncher.discoverWhatsAppApps(this, forceRefresh = true)
                if (profile.requiresExplicitAutoTarget) {
                    val first = discovered.firstOrNull()
                    if (first == null) {
                        app.preferences.accessibilityQuickJoin = false
                        isBinding = true
                        binding.accessibilityQuickJoinSwitch.isChecked = false
                        isBinding = false
                        Toast.makeText(this, R.string.profile_explicit_target_required, Toast.LENGTH_LONG).show()
                        renderAccessibilityState()
                        return@setOnCheckedChangeListener
                    }
                    app.preferences.preferredTarget = PreferredTarget.AUTO
                    app.preferences.selectedWhatsAppPackage = first.packageName
                    app.preferences.selectedWhatsAppLabel = first.label
                } else {
                    app.preferences.preferredTarget = PreferredTarget.AUTO
                    app.preferences.selectedWhatsAppPackage = null
                    app.preferences.selectedWhatsAppLabel = null
                }
                isBinding = true
                binding.targetRadioGroup.check(R.id.targetAutoRadio)
                isBinding = false
            }
            app.preferences.accessibilityQuickJoin = true
            if (AccessibilityStatus.isQuickJoinServiceConnectedLocally(this)) {
                renderAccessibilityState()
            } else {
                showAccessibilityPermissionExplanation()
            }
        }

        binding.saveJoinDelayButton.setOnClickListener {
            val requested = binding.joinDelayInput.text?.toString()?.trim()?.toIntOrNull()
            if (requested == null || requested !in
                AutomationPolicy.MIN_DELAY_SECONDS..AutomationPolicy.MAX_DELAY_SECONDS
            ) {
                binding.joinDelayLayout.error = getString(
                    R.string.join_delay_error,
                    AutomationPolicy.MIN_DELAY_SECONDS,
                    AutomationPolicy.MAX_DELAY_SECONDS
                )
                return@setOnClickListener
            }

            binding.joinDelayLayout.error = null
            app.preferences.accessibilityJoinDelaySeconds = requested
            binding.joinDelayInput.setText(
                app.preferences.accessibilityJoinDelaySeconds.toString()
            )
            Toast.makeText(this, R.string.join_delay_saved, Toast.LENGTH_SHORT).show()
        }

        binding.openAccessibilityServiceDetailsButton.setOnClickListener {
            val opened = AccessibilitySettingsLauncher.openServiceDetails(this)
            Toast.makeText(
                this,
                if (opened) R.string.accessibility_route_service_details_started
                else R.string.accessibility_route_service_details_unavailable,
                Toast.LENGTH_LONG
            ).show()
        }

        binding.openAccessibilitySettingsButton.setOnClickListener {
            val opened = AccessibilitySettingsLauncher.openAccessibilityList(this)
            Toast.makeText(
                this,
                if (opened) R.string.accessibility_route_list_started
                else R.string.accessibility_route_list_unavailable,
                Toast.LENGTH_LONG
            ).show()
        }

        binding.openCurrentProfileAppInfoButton.setOnClickListener {
            val opened = AccessibilitySettingsLauncher.openCurrentProfileAppInfo(this)
            Toast.makeText(
                this,
                if (opened) R.string.accessibility_route_app_info_started
                else R.string.accessibility_route_app_info_unavailable,
                Toast.LENGTH_LONG
            ).show()
        }

        binding.targetRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isBinding) return@setOnCheckedChangeListener
            val profile = ProfileEnvironment.current(this)
            if (checkedId == R.id.targetAutoRadio && profile.requiresExplicitAutoTarget) {
                val first = WhatsAppLauncher.discoverWhatsAppApps(this, forceRefresh = true).firstOrNull()
                if (first == null) {
                    Toast.makeText(this, R.string.profile_explicit_target_required, Toast.LENGTH_LONG).show()
                    bindCurrentSettings()
                    applyProfileTargetGuards()
                    return@setOnCheckedChangeListener
                }
                // AUTO in an isolated profile is accepted only with one concrete profile-local
                // package persisted behind it; the run is still locked to that exact package.
                app.preferences.preferredTarget = PreferredTarget.AUTO
                app.preferences.selectedWhatsAppPackage = first.packageName
                app.preferences.selectedWhatsAppLabel = first.label
            } else {
                app.preferences.selectedWhatsAppPackage = null
                app.preferences.selectedWhatsAppLabel = null
                app.preferences.preferredTarget = when (checkedId) {
                    R.id.targetPersonalRadio -> PreferredTarget.PERSONAL
                    R.id.targetBusinessRadio -> PreferredTarget.BUSINESS
                    R.id.targetCloneRadio -> PreferredTarget.CLONED
                    R.id.targetBrowserRadio -> PreferredTarget.BROWSER
                    else -> PreferredTarget.AUTO
                }
            }
            if (app.preferences.preferredTarget == PreferredTarget.BROWSER &&
                app.preferences.accessibilityQuickJoin
            ) {
                app.preferences.accessibilityQuickJoin = false
                app.preferences.stopAccessibilityBatch()
                QuickJoinNotification.cancel(this)
                renderAccessibilityState()
            }
            applyProfileTargetGuards()
        }

        binding.themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isBinding) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.themeLightRadio -> ThemeMode.LIGHT
                R.id.themeDarkRadio -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            app.preferences.themeMode = mode
            AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        }
    }

    private fun showAccessibilityPermissionExplanation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accessibility_permission_title)
            .setMessage(R.string.accessibility_permission_message)
            .setNegativeButton(R.string.cancel) { _, _ ->
                app.preferences.accessibilityQuickJoin = false
                app.preferences.stopAccessibilityBatch()
                QuickJoinNotification.cancel(this)
                renderAccessibilityState()
            }
            .setPositiveButton(R.string.open_accessibility_settings) { _, _ ->
                openAccessibilitySettings()
            }
            .setOnCancelListener {
                app.preferences.accessibilityQuickJoin = false
                app.preferences.stopAccessibilityBatch()
                QuickJoinNotification.cancel(this)
                renderAccessibilityState()
            }
            .show()
    }

    private fun openAccessibilitySettings() {
        val profile = ProfileEnvironment.current(this)
        val opened = if (profile.managedProfile) {
            // Managed profiles commonly redirect Settings to the parent profile. Try the exact
            // service component first, then fall back to the global list without bypassing policy.
            AccessibilitySettingsLauncher.openServiceDetails(this) ||
                AccessibilitySettingsLauncher.openAccessibilityList(this)
        } else {
            AccessibilitySettingsLauncher.open(this)
        }
        if (!opened) {
            Toast.makeText(this, R.string.accessibility_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderAccessibilityState() {
        val readiness = AccessibilityStatus.readiness(this)
        val runtime = ProfileAccessibilityRuntime.snapshot(this)
        val requested = app.preferences.accessibilityQuickJoin
        val localReady = readiness.systemEnabled && readiness.localServiceConnected

        isBinding = true
        binding.accessibilityQuickJoinSwitch.isChecked = requested
        isBinding = false

        binding.accessibilityStatusText.setText(
            when {
                localReady && requested -> R.string.accessibility_status_active_local_profile
                readiness.systemEnabled && !readiness.localServiceConnected -> R.string.accessibility_status_wrong_profile
                readiness.systemEnabled -> R.string.accessibility_status_system_only
                requested -> R.string.accessibility_status_permission_needed
                else -> R.string.accessibility_status_off
            }
        )

        val targetPackage = WhatsAppLauncher.validateTarget(
            this,
            app.preferences.preferredTarget,
            app.preferences.selectedWhatsAppPackage
        ).packageName
        val capability = runtime.capability(readiness.systemEnabled, targetPackage)
        binding.profileControlDiagnosticText.text = getString(
            R.string.profile_control_diagnostic_format,
            runtime.profileKey,
            if (readiness.systemEnabled) getString(R.string.yes) else getString(R.string.no),
            if (runtime.localServiceConnected) getString(R.string.yes) else getString(R.string.no),
            runtime.lastEventPackage ?: "—",
            if (runtime.rootAvailable) runtime.rootPackage ?: getString(R.string.yes) else getString(R.string.no),
            capability.name
        )

        val policy = ProfileAccessibilityPolicyInspector.inspect(this)
        val policyLabel = when (policy.policyVisibility) {
            AccessibilityPolicyVisibility.NOT_MANAGED -> getString(R.string.accessibility_policy_not_managed)
            AccessibilityPolicyVisibility.ALL_ALLOWED -> getString(R.string.accessibility_policy_all_allowed)
            AccessibilityPolicyVisibility.ALLOWLIST_INCLUDES_APP -> getString(R.string.accessibility_policy_app_allowed)
            AccessibilityPolicyVisibility.BLOCKED_BY_POLICY -> getString(R.string.accessibility_policy_blocked)
            AccessibilityPolicyVisibility.QUERY_NOT_AVAILABLE_TO_STANDARD_APP ->
                getString(R.string.accessibility_policy_query_unavailable)
        }
        val activationLabel = when (policy.activationState) {
            ProfileAccessibilityActivationState.READY -> getString(R.string.accessibility_activation_ready)
            ProfileAccessibilityActivationState.SERVICE_COMPONENT_NOT_VISIBLE ->
                getString(R.string.accessibility_activation_component_missing)
            ProfileAccessibilityActivationState.PARENT_ACCESSIBILITY_TOGGLE_REQUIRED ->
                getString(R.string.accessibility_activation_parent_toggle_required)
            ProfileAccessibilityActivationState.ENABLED_SETTING_PRESENT_BUT_MANAGER_DISABLED ->
                getString(R.string.accessibility_activation_setting_present_not_active)
            ProfileAccessibilityActivationState.LOCAL_SERVICE_NOT_CONNECTED ->
                getString(R.string.accessibility_activation_local_not_connected)
            ProfileAccessibilityActivationState.WAITING_FOR_LOCAL_BIND ->
                getString(R.string.accessibility_activation_waiting_bind)
        }
        binding.profileAccessibilityPolicyText.text = getString(
            R.string.profile_accessibility_policy_format,
            if (policy.serviceComponentVisible) getString(R.string.yes) else getString(R.string.no),
            when (policy.secureEnabledSettingContainsService) {
                true -> getString(R.string.yes)
                false -> getString(R.string.no)
                null -> getString(R.string.unknown)
            },
            policyLabel,
            policy.policyManagerPackage ?: "—",
            activationLabel
        )
    }

    private fun renderNativeProfileEngineState() {
        val snapshot = NativeProfileEngineRouter.inspect(this, app.preferences.automationBackend)
        val work = snapshot.workController
        val backendLabel = snapshot.decision.backend?.name ?: "—"
        binding.nativeProfileEngineStatusText.text = getString(
            R.string.native_profile_engine_format,
            snapshot.profileKey,
            snapshot.profileClass.name,
            if (snapshot.accessibilityLocalReady) getString(R.string.yes) else getString(R.string.no),
            if (snapshot.shizukuReady) getString(R.string.yes) else getString(R.string.no),
            if (work.selfProfileOwner) getString(R.string.yes) else getString(R.string.no),
            work.policyManagerPackage ?: "—",
            work.accessibilityPolicyState.name,
            backendLabel,
            snapshot.decision.setupAction.name,
            snapshot.decision.reasonCode
        )
        val canApplyPolicy = work.managedProfile && work.canManageAccessibilityPolicy
        binding.applyWorkAccessibilityPolicyButton.isEnabled = canApplyPolicy || snapshot.shizukuReady
        binding.applyWorkAccessibilityPolicyButton.setText(
            if (canApplyPolicy) R.string.apply_work_accessibility_policy
            else R.string.use_work_shizuku_runtime
        )
    }

    private fun renderShizukuState() {
        val status = ShizukuBridge.status()
        binding.requestShizukuPermissionButton.isEnabled = status.binderAlive && !status.permissionGranted
        binding.testShizukuButton.isEnabled = status.ready
        binding.shizukuStatusText.text = when {
            !status.binderAlive -> getString(R.string.shizuku_status_off)
            !status.permissionGranted -> getString(R.string.shizuku_status_permission)
            else -> getString(
                R.string.shizuku_status_ready,
                status.serverUid ?: -1,
                if (status.userServiceBound) getString(R.string.shizuku_status_bound) else ""
            )
        }
    }

    private fun renderInstalledApps() {
        val personalInstalled = WhatsAppLauncher.canHandleInvite(
            this,
            WhatsAppLauncher.WHATSAPP_PACKAGE
        )
        val businessInstalled = WhatsAppLauncher.canHandleInvite(
            this,
            WhatsAppLauncher.WHATSAPP_BUSINESS_PACKAGE
        )
        binding.personalStatusText.text = getString(
            if (personalInstalled) R.string.installed else R.string.not_installed
        )
        binding.businessStatusText.text = getString(
            if (businessInstalled) R.string.installed else R.string.not_installed
        )
    }

    /** Keep legacy settings from selecting an impossible cross-profile/implicit target. */
    private fun applyProfileTargetGuards() {
        val profile = ProfileEnvironment.current(this)
        binding.targetAutoRadio.isEnabled = !profile.requiresExplicitAutoTarget ||
            !app.preferences.selectedWhatsAppPackage.isNullOrBlank()
        binding.targetPersonalRadio.isEnabled = WhatsAppLauncher.canHandleInvite(
            this,
            WhatsAppLauncher.WHATSAPP_PACKAGE
        )
        binding.targetBusinessRadio.isEnabled = WhatsAppLauncher.canHandleInvite(
            this,
            WhatsAppLauncher.WHATSAPP_BUSINESS_PACKAGE
        )
        binding.targetCloneRadio.isEnabled = WhatsAppLauncher.canHandleInvite(
            this,
            WhatsAppLauncher.WHATSAPP_CLONED_PACKAGE
        )
        // Browser mode is intentionally not an Accessibility automation target.
        binding.targetBrowserRadio.isEnabled = !app.preferences.accessibilityQuickJoin
    }
}
