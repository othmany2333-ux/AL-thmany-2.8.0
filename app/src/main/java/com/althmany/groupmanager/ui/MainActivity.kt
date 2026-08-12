package com.althmany.groupmanager.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.althmany.groupmanager.BuildConfig
import com.althmany.groupmanager.GroupManagerApp
import com.althmany.groupmanager.R
import com.althmany.groupmanager.accessibility.AccessibilityStatus
import com.althmany.groupmanager.accessibility.QuickJoinAccessibilityService
import com.althmany.groupmanager.data.AppPreferences
import com.althmany.groupmanager.databinding.ActivityMainBinding
import com.althmany.groupmanager.domain.AutomationPolicy
import com.althmany.groupmanager.domain.AutomationSchedule
import com.althmany.groupmanager.domain.AutomationStage
import com.althmany.groupmanager.domain.AutomationStopReason
import com.althmany.groupmanager.domain.HybridBackendPolicy
import com.althmany.groupmanager.domain.NativeEngineSetupAction
import com.althmany.groupmanager.domain.ProfileControlPolicy
import com.althmany.groupmanager.domain.RestrictionHandlingMode
import com.althmany.groupmanager.domain.RuntimeSpeedMode
import com.althmany.groupmanager.domain.RuntimeSpeedProfilePolicy
import com.althmany.groupmanager.domain.SessionRules
import com.althmany.groupmanager.model.AutomationBackend
import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkSource
import com.althmany.groupmanager.model.PreferredTarget
import com.althmany.groupmanager.util.AccessibilitySettingsLauncher
import com.althmany.groupmanager.util.DocumentIO
import com.althmany.groupmanager.util.GroupJoinerResultStore
import com.althmany.groupmanager.util.LaunchDestination
import com.althmany.groupmanager.util.ProfileEnvironment
import com.althmany.groupmanager.util.NativeProfileEngineRouter
import com.althmany.groupmanager.util.QuickJoinNotification
import com.althmany.groupmanager.util.RuntimeHealthMonitor
import com.althmany.groupmanager.util.WhatsAppLauncher
import com.althmany.groupmanager.shizuku.ShizukuAutomationService
import com.althmany.groupmanager.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * AL-thmany 1.6 Lightning Resume Compact dashboard. After the one-time Android Accessibility permission,
 * the hands-free workflow is: paste/import links and choose WhatsApp; smart start handles the rest.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val app: GroupManagerApp get() = application as GroupManagerApp
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application as GroupManagerApp)
    }

    private val linksAdapter = GroupLinkAdapter(
        onOpen = { viewModel.reopen(it.id) },
        onCopy = ::copyLink,
        onDelete = ::confirmDeleteLink
    )

    private var pendingExportContent: String? = null
    private var pendingAutoStartAfterSettings = false
    private var pendingQueueContinuationAfterSettings = false
    private var bindingTargetSelection = false
    private var smartAutoStartJob: Job? = null
    private var linkAnalysisJob: Job? = null
    private var accessibilityReconnectJob: Job? = null
    private var lastAutomaticInputHash: Int? = null
    private var detectedLinkCount: Int = 0
    private var advancedSettingsVisible = false
    private var runtimeSpeedBinding = false
    private var autoResumeKickPending = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast(R.string.notification_permission_denied)
    }

    private val importDocumentsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importDocuments(uris)
    }

    private val createReportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val content = pendingExportContent
        pendingExportContent = null
        if (uri != null && content != null) {
            DocumentIO.writeText(contentResolver, uri, content)
                .onSuccess { toast(R.string.report_exported) }
                .onFailure { toast(R.string.report_export_failed) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureToolbar()
        configureLinkList()
        configureTargetSelector()
        configureTimingControls()
        configureActions()
        observeViewModel()
        handleControlIntent(intent)
        handleIncomingIntent(intent)
        maybeRequestNotificationPermission()
        renderRuntimeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        renderRuntimeState()

        if (!app.preferences.accessibilityBatchRunning &&
            app.preferences.automationStopReason == AutomationStopReason.SESSION_COMPLETE
        ) {
            lastAutomaticInputHash = null
        }

        if (app.preferences.autoResumeCurrentRun && !autoResumeKickPending) {
            val recoverable = app.preferences.automationStopReason in setOf(
                AutomationStopReason.SERVICE_DISABLED,
                AutomationStopReason.TARGET_UNSUPPORTED,
                AutomationStopReason.OPEN_FAILED,
                AutomationStopReason.BROWSER_FALLBACK,
                AutomationStopReason.UNKNOWN_SCREEN,
                AutomationStopReason.ACTION_TIMEOUT,
                AutomationStopReason.RUNTIME_CIRCUIT_BREAKER
            )
            if (recoverable && !app.preferences.accessibilityBatchRunning &&
                !app.preferences.hasScheduledStart && isAnyAutomationEngineReady()
            ) {
                autoResumeKickPending = true
                binding.root.postDelayed({
                    autoResumeKickPending = false
                    if (canContinueQueuedBatch() && !app.preferences.accessibilityBatchRunning) {
                        startAutomaticRun(allowQueuedContinuation = true)
                    }
                }, 350L)
            }
        }

        if (pendingAutoStartAfterSettings &&
            AccessibilityStatus.isQuickJoinServiceEnabled(this@MainActivity)
        ) {
            val continueQueuedBatch = pendingQueueContinuationAfterSettings
            pendingAutoStartAfterSettings = false
            pendingQueueContinuationAfterSettings = false
            binding.root.postDelayed(
                { startAutomaticRun(allowQueuedContinuation = continueQueuedBatch) },
                90L
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleControlIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun configureToolbar() {
        val displayVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug")
        binding.toolbar.subtitle = getString(R.string.unified_toolbar_subtitle, displayVersion)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun configureLinkList() {
        binding.linksRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = linksAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(true)
            itemAnimator = null
            recycledViewPool.setMaxRecycledViews(0, 24)
        }
    }

    private fun configureTargetSelector() {
        if (app.preferences.preferredTarget == PreferredTarget.BROWSER) {
            app.preferences.preferredTarget = PreferredTarget.AUTO
        }
        bindingTargetSelection = true
        val checkedId = when (app.preferences.preferredTarget) {
            PreferredTarget.AUTO -> R.id.targetAutoButton
            PreferredTarget.PERSONAL -> R.id.targetPersonalButton
            PreferredTarget.BUSINESS -> R.id.targetBusinessButton
            PreferredTarget.CLONED -> R.id.targetCloneButton
            PreferredTarget.BROWSER -> R.id.targetAutoButton
        }
        binding.targetToggleGroup.check(checkedId)
        bindingTargetSelection = false

        binding.targetToggleGroup.addOnButtonCheckedListener { _, checked, isChecked ->
            if (!isChecked || bindingTargetSelection) return@addOnButtonCheckedListener
            val target = when (checked) {
                R.id.targetPersonalButton -> PreferredTarget.PERSONAL
                R.id.targetBusinessButton -> PreferredTarget.BUSINESS
                R.id.targetCloneButton -> PreferredTarget.CLONED
                else -> PreferredTarget.AUTO
            }
            app.preferences.selectedWhatsAppPackage = null
            app.preferences.selectedWhatsAppLabel = null
            viewModel.setPreferredTarget(target)
            renderInstalledTargets()
            scheduleSmartAutoStart()
        }


        binding.chooseInstalledWhatsAppButton.setOnClickListener { showInstalledWhatsAppPicker() }
        binding.testWhatsAppTargetButton.setOnClickListener { testSelectedWhatsAppTarget() }
        binding.communityTraversalSwitch.isChecked = app.preferences.communityTraversalEnabled
        binding.communityTraversalSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.communityTraversalEnabled = checked
        }
    }

    private fun configureTimingControls() = with(binding) {
        configureRuntimeSpeedControls()

        delaySlider.value = app.preferences.interLinkDelayMs.toFloat()
        delayValueText.text = formatInterLinkDelay(app.preferences.interLinkDelayMs)
        delaySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val delayMs = value.toInt()
            app.preferences.interLinkDelayMs = delayMs
            if (delayMs == AutomationPolicy.FAST_INTER_LINK_DELAY_MS) {
                app.preferences.fastHandsFreeMode = true
            }
            delayValueText.text = formatInterLinkDelay(delayMs)
            updateSessionEstimate()
        }

        actionTimeoutSlider.value = app.preferences.accessibilityActionTimeoutSeconds.toFloat()
        actionTimeoutValueText.text = getString(
            R.string.nebula_action_timeout_format,
            app.preferences.accessibilityActionTimeoutSeconds
        )
        actionTimeoutSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val seconds = value.toInt()
            app.preferences.accessibilityActionTimeoutSeconds = seconds
            actionTimeoutValueText.text = getString(R.string.nebula_action_timeout_format, seconds)
            updateSessionEstimate()
        }

        startDelaySlider.value = app.preferences.startDelaySeconds.toFloat()
        startDelayValueText.text = getString(
            R.string.aurora_start_delay_format,
            app.preferences.startDelaySeconds
        )
        startDelaySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            app.preferences.startDelaySeconds = value.toInt()
            startDelayValueText.text = getString(
                R.string.aurora_start_delay_format,
                app.preferences.startDelaySeconds
            )
        }

        val checked = when (app.preferences.startMode) {
            AppPreferences.START_MODE_DELAY -> R.id.startAfterButton
            AppPreferences.START_MODE_CLOCK -> R.id.startClockButton
            else -> R.id.startNowButton
        }
        startModeToggleGroup.check(checked)
        startModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            app.preferences.startMode = when (checkedId) {
                R.id.startAfterButton -> AppPreferences.START_MODE_DELAY
                R.id.startClockButton -> AppPreferences.START_MODE_CLOCK
                else -> AppPreferences.START_MODE_NOW
            }
            renderTimingControls()
            scheduleSmartAutoStart()
        }

        chooseDateButton.setOnClickListener { showDatePicker() }
        chooseClockButton.setOnClickListener { showClockPicker() }
        renderTimingControls()
        updateSessionEstimate()
    }

    private fun configureRuntimeSpeedControls() = with(binding) {
        runtimeSpeedBinding = true
        speedModeToggleGroup.check(
            when (app.preferences.runtimeSpeedMode) {
                RuntimeSpeedMode.STABLE -> R.id.speedStableButton
                RuntimeSpeedMode.FAST -> R.id.speedFastButton
                RuntimeSpeedMode.TURBO -> R.id.speedTurboButton
                RuntimeSpeedMode.MAX -> R.id.speedMaxButton
                RuntimeSpeedMode.CUSTOM -> R.id.speedCustomButton
            }
        )
        customScanSlider.value = app.preferences.customScanMs.toFloat()
        customPostTapSlider.value = app.preferences.customPostTapMs.toFloat()
        customInterLinkSlider.value = app.preferences.customInterLinkMs.toFloat()
        runtimeSpeedBinding = false

        speedModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || runtimeSpeedBinding) return@addOnButtonCheckedListener
            app.preferences.runtimeSpeedMode = when (checkedId) {
                R.id.speedStableButton -> RuntimeSpeedMode.STABLE
                R.id.speedTurboButton -> RuntimeSpeedMode.TURBO
                R.id.speedMaxButton -> RuntimeSpeedMode.MAX
                R.id.speedCustomButton -> RuntimeSpeedMode.CUSTOM
                else -> RuntimeSpeedMode.FAST
            }
            applyRuntimeSpeedCompatibilityValues()
            renderRuntimeSpeedControls()
        }

        customScanSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            app.preferences.customScanMs = value.toInt()
            customScanValueText.text = getString(
                R.string.runtime_ms_format,
                app.preferences.customScanMs
            )
        }
        customPostTapSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            app.preferences.customPostTapMs = value.toInt()
            customPostTapValueText.text = getString(
                R.string.runtime_ms_format,
                app.preferences.customPostTapMs
            )
        }
        customInterLinkSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            app.preferences.customInterLinkMs = value.toInt()
            app.preferences.interLinkDelayMs = app.preferences.customInterLinkMs
            customInterLinkValueText.text = getString(
                R.string.runtime_ms_format,
                app.preferences.customInterLinkMs
            )
            delaySlider.value = app.preferences.interLinkDelayMs.toFloat()
            delayValueText.text = formatInterLinkDelay(app.preferences.interLinkDelayMs)
        }

        renderRuntimeSpeedControls()
    }

    private fun applyRuntimeSpeedCompatibilityValues() {
        val profile = app.preferences.runtimeSpeedProfile()
        app.preferences.fastHandsFreeMode =
            RuntimeSpeedProfilePolicy.isFast(app.preferences.runtimeSpeedMode)
        app.preferences.interLinkDelayMs = profile.interLinkDelayMs.toInt()
        binding.fastHandsFreeSwitch.isChecked = app.preferences.fastHandsFreeMode
        binding.delaySlider.value = app.preferences.interLinkDelayMs.toFloat()
        binding.delayValueText.text = formatInterLinkDelay(app.preferences.interLinkDelayMs)
    }

    private fun renderRuntimeSpeedControls() = with(binding) {
        val custom = app.preferences.runtimeSpeedMode == RuntimeSpeedMode.CUSTOM
        customSpeedControls.visibility = if (custom) View.VISIBLE else View.GONE
        customScanValueText.text = getString(
            R.string.runtime_ms_format,
            app.preferences.customScanMs
        )
        customPostTapValueText.text = getString(
            R.string.runtime_ms_format,
            app.preferences.customPostTapMs
        )
        customInterLinkValueText.text = getString(
            R.string.runtime_ms_format,
            app.preferences.customInterLinkMs
        )
    }

    private fun renderAdvancedSettings() = with(binding) {
        advancedSmartCard.visibility = if (advancedSettingsVisible) View.VISIBLE else View.GONE
        advancedScheduleCard.visibility = if (advancedSettingsVisible) View.VISIBLE else View.GONE
        advancedSettingsButton.setText(
            if (advancedSettingsVisible) R.string.hide_advanced_settings
            else R.string.advanced_settings
        )
    }

    private fun showDatePicker() {
        val initialUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(Calendar.YEAR, app.preferences.scheduledDateYear)
            set(Calendar.MONTH, app.preferences.scheduledDateMonth)
            set(Calendar.DAY_OF_MONTH, app.preferences.scheduledDateDay)
        }.timeInMillis

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.quantum_choose_date)
            .setSelection(initialUtc)
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = selection
            }
            app.preferences.scheduledDateYear = utc.get(Calendar.YEAR)
            app.preferences.scheduledDateMonth = utc.get(Calendar.MONTH)
            app.preferences.scheduledDateDay = utc.get(Calendar.DAY_OF_MONTH)
            renderTimingControls()
            scheduleSmartAutoStart()
        }
        picker.show(supportFragmentManager, "althmany_start_date")
    }

    private fun showClockPicker() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(app.preferences.scheduledClockHour)
            .setMinute(app.preferences.scheduledClockMinute)
            .setTitleText(R.string.aurora_choose_time)
            .build()
        picker.addOnPositiveButtonClickListener {
            app.preferences.scheduledClockHour = picker.hour
            app.preferences.scheduledClockMinute = picker.minute
            renderTimingControls()
            scheduleSmartAutoStart()
        }
        picker.show(supportFragmentManager, "althmany_start_time")
    }

    private fun renderTimingControls() = with(binding) {
        val mode = app.preferences.startMode
        startDelayControls.visibility = if (mode == AppPreferences.START_MODE_DELAY) View.VISIBLE else View.GONE
        startClockControls.visibility = if (mode == AppPreferences.START_MODE_CLOCK) View.VISIBLE else View.GONE

        val clock = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, app.preferences.scheduledDateYear)
            set(Calendar.MONTH, app.preferences.scheduledDateMonth)
            set(Calendar.DAY_OF_MONTH, app.preferences.scheduledDateDay)
            set(Calendar.HOUR_OF_DAY, app.preferences.scheduledClockHour)
            set(Calendar.MINUTE, app.preferences.scheduledClockMinute)
        }
        selectedDateText.text = DateFormat.getMediumDateFormat(this@MainActivity).format(clock.time)
        selectedClockText.text = DateFormat.getTimeFormat(this@MainActivity).format(clock.time)

        if (!app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart) {
            startAutomationButton.setText(
                when {
                    canContinueQueuedBatch() -> R.string.autopilot_continue_next_batch
                    mode == AppPreferences.START_MODE_NOW -> R.string.swift_start_button
                    else -> R.string.aurora_start_scheduled_button
                }
            )
        }
    }

    private fun requestedStartAtMillis(): Long {
        val now = System.currentTimeMillis()
        return when (app.preferences.startMode) {
            AppPreferences.START_MODE_DELAY -> AutomationSchedule.delayedStart(
                now,
                app.preferences.startDelaySeconds
            )
            AppPreferences.START_MODE_CLOCK -> AutomationSchedule.exactDateTimeStart(
                year = app.preferences.scheduledDateYear,
                month = app.preferences.scheduledDateMonth,
                day = app.preferences.scheduledDateDay,
                hour = app.preferences.scheduledClockHour,
                minute = app.preferences.scheduledClockMinute
            )
            else -> 0L
        }
    }

    private fun configureActions() = with(binding) {
        autoResumeSwitch.isChecked = app.preferences.autoResumeCurrentRun
        autoResumeHintText.setText(
            if (app.preferences.autoResumeCurrentRun) R.string.auto_resume_enabled_hint
            else R.string.auto_resume_disabled_hint
        )
        autoResumeSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.autoResumeCurrentRun = checked
            if (compactAutoResumeSwitch.isChecked != checked) {
                compactAutoResumeSwitch.isChecked = checked
            }
            autoResumeHintText.setText(
                if (checked) R.string.auto_resume_enabled_hint
                else R.string.auto_resume_disabled_hint
            )
            toast(if (checked) R.string.auto_resume_enabled else R.string.auto_resume_disabled)
        }

        autoPauseOutsideWhatsAppSwitch.isChecked = app.preferences.autoPauseOutsideWhatsApp
        autoPauseOutsideWhatsAppSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.autoPauseOutsideWhatsApp = checked
        }

        returnToAppSwitch.isChecked = app.preferences.returnToAppOnRunComplete
        returnToAppSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.returnToAppOnRunComplete = checked
        }

        autoStartSwitch.isChecked = app.preferences.smartAutoStart
        autoStartSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.smartAutoStart = checked
            autoStartHintText.setText(
                if (checked) R.string.autopilot_auto_start_hint
                else R.string.autopilot_auto_start_disabled
            )
            toast(
                if (checked) R.string.autopilot_auto_start_enabled
                else R.string.autopilot_auto_start_disabled
            )
            if (checked) scheduleSmartAutoStart() else smartAutoStartJob?.cancel()
        }

        fastHandsFreeSwitch.isChecked = app.preferences.fastHandsFreeMode
        fastHandsFreeHintText.setText(
            if (app.preferences.fastHandsFreeMode) R.string.swift_fast_description
            else R.string.quantum_fast_handsfree_balanced_description
        )
        fastHandsFreeSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.fastHandsFreeMode = checked
            fastHandsFreeHintText.setText(
                if (checked) R.string.swift_fast_description
                else R.string.quantum_fast_handsfree_balanced_description
            )
            if (checked) applyFastHandsFreePreset()
            updateSessionEstimate()
            toast(
                if (checked) R.string.quantum_fast_mode_enabled
                else R.string.quantum_fast_mode_disabled
            )
            if (checked) scheduleSmartAutoStart()
        }

        linksEditText.doAfterTextChanged {
            val text = it?.toString().orEmpty()
            scheduleLinkAnalysis(text)
            if (text.isBlank()) lastAutomaticInputHash = null
            scheduleSmartAutoStart()
        }

        pasteButton.setOnClickListener { pasteFromClipboard() }
        importButton.setOnClickListener {
            importDocumentsLauncher.launch(arrayOf("text/plain", "text/csv", "text/*"))
        }
        clearInputButton.setOnClickListener { linksEditText.text?.clear() }
        linksInputLayout.setEndIconOnClickListener { linksEditText.text?.clear() }
        setupServiceButton.setOnClickListener {
            val readiness = AccessibilityStatus.readiness(this@MainActivity)
            when {
                isAnyAutomationEngineReady() -> renderRuntimeState()
                readiness.systemEnabled ->
                    waitForLocalAccessibilityBind(
                        allowQueuedContinuation = false,
                        startAfterBind = false
                    )
                else -> showOneTimeSetupDialog(false)
            }
        }
        startAutomationButton.setOnClickListener { startAutomaticRun(allowQueuedContinuation = true) }
        resumeLastRunButton.setOnClickListener { startAutomaticRun(allowQueuedContinuation = true) }
        advancedSettingsButton.setOnClickListener {
            advancedSettingsVisible = !advancedSettingsVisible
            renderAdvancedSettings()
        }

        compactAutoResumeSwitch.isChecked = app.preferences.autoResumeCurrentRun
        compactAutoResumeSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.autoResumeCurrentRun = checked
            if (autoResumeSwitch.isChecked != checked) autoResumeSwitch.isChecked = checked
        }

        continueOnRestrictionSwitch.isChecked =
            app.preferences.restrictionHandlingMode == RestrictionHandlingMode.SKIP_AND_CONTINUE
        continueOnRestrictionSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.restrictionHandlingMode =
                if (checked) RestrictionHandlingMode.SKIP_AND_CONTINUE
                else RestrictionHandlingMode.STOP_RUN
        }

        renderAdvancedSettings()
        pauseAutomationButton.setOnClickListener { toggleAutomationPause() }
        stopAutomationButton.setOnClickListener { stopAutomationFromUi() }
        skipCurrentButton.setOnClickListener { viewModel.markCurrentSkipped() }
        exportButton.setOnClickListener { viewModel.exportCsv() }
        shareButton.setOnClickListener { viewModel.shareReport() }
        clearSessionButton.setOnClickListener { confirmClearSession() }
        cancelScheduleButton.setOnClickListener { cancelScheduledStart() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
                launch {
                    // Runtime controls are cheap to refresh. The full large-queue snapshot is
                    // refreshed on lifecycle/events instead of every 850 ms, which previously
                    // caused repeated SQLite reads and RecyclerView diff work while running.
                    while (true) {
                        renderRuntimeState()
                        delay(900L)
                    }
                }
            }
        }
    }

    private fun render(state: MainUiState) = with(binding) {
        loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        startAutomationButton.isEnabled = !state.isLoading

        val snapshot = state.snapshot
        val showSession = snapshot != null
        if (showSession && sessionCard.visibility != View.VISIBLE) {
            sessionCard.alpha = 0f
            sessionCard.visibility = View.VISIBLE
            sessionCard.animate().alpha(1f).setDuration(220L).start()
        } else if (!showSession) {
            sessionCard.visibility = View.GONE
        }
        idleCard.visibility = if (snapshot == null) View.VISIBLE else View.GONE
        linksAdapter.submitList(snapshot?.let(::uiLinkWindow).orEmpty())

        if (snapshot != null) {
            val stats = snapshot.stats
            sessionProgressIndicator.progress = stats.progressPercent
            sessionProgressText.text = getString(
                R.string.autopilot_progress_format,
                stats.completed,
                stats.total,
                stats.progressPercent
            )
            joinedCountText.text = "${stats.joined}\n${getString(R.string.status_joined)}"
            requestedCountText.text = "${stats.requested}\n${getString(R.string.status_requested)}"
            failedCountText.text = "${stats.failed}\n${getString(R.string.status_failed)}"
            remainingCountText.text = "${stats.remaining}\n${getString(R.string.autopilot_remaining)}"

            val current = SessionRules.currentOpened(snapshot.links)
                ?: SessionRules.nextActionable(snapshot.links)
            currentLinkText.text = if (current == null) {
                getString(R.string.autopilot_no_current_link)
            } else {
                getString(R.string.autopilot_current_link_format, current.position + 1, stats.total)
            }

            val running = app.preferences.accessibilityBatchRunning
            pauseAutomationButton.visibility = if (running) View.VISIBLE else View.GONE
            stopAutomationButton.visibility = if (running) View.VISIBLE else View.GONE
            skipCurrentButton.visibility = if (running && current != null) View.VISIBLE else View.GONE
        }

        renderRuntimeState()
    }

    private fun isAnyAutomationEngineReady(): Boolean {
        val accessibilityReady =
            AccessibilityStatus.isQuickJoinServiceConnectedLocally(this@MainActivity)
        if (accessibilityReady) return true
        return runCatching { ShizukuBridge.status().ready }.getOrDefault(false)
    }

    private fun renderRuntimeState() = with(binding) {
        val readiness = AccessibilityStatus.readiness(this@MainActivity)
        val serviceEnabled = isAnyAutomationEngineReady()
        val permissionConfigured = readiness.systemEnabled
        serviceStatusText.setText(
            when {
                serviceEnabled -> R.string.autopilot_service_ready
                permissionConfigured -> R.string.autopilot_service_reconnecting
                else -> R.string.autopilot_service_needs_setup
            }
        )
        serviceStatusText.setTextColor(
            ContextCompat.getColor(
                this@MainActivity,
                if (serviceEnabled) R.color.status_joined else R.color.status_skipped
            )
        )
        setupServiceButton.setText(
            when {
                serviceEnabled -> R.string.autopilot_service_enabled_button
                permissionConfigured -> R.string.autopilot_recheck_service
                else -> R.string.autopilot_setup_once
            }
        )
        setupServiceButton.isEnabled = !serviceEnabled

        val running = app.preferences.accessibilityBatchRunning
        val paused = app.preferences.accessibilityPaused
        val scheduled = app.preferences.hasScheduledStart
        val controlsUnlocked = !running && !scheduled
        startAutomationButton.isEnabled = (!running || paused) && !scheduled
        resumeLastRunButton.visibility =
            if (!running && !scheduled && canContinueQueuedBatch()) View.VISIBLE else View.GONE
        resumeLastRunButton.isEnabled = !running && !scheduled
        compactAutoResumeSwitch.isEnabled = true
        autoStartSwitch.isEnabled = controlsUnlocked
        autoResumeSwitch.isEnabled = true
        autoPauseOutsideWhatsAppSwitch.isEnabled = true
        returnToAppSwitch.isEnabled = true
        chooseInstalledWhatsAppButton.isEnabled = controlsUnlocked
        fastHandsFreeSwitch.isEnabled = controlsUnlocked
        linksEditText.isEnabled = controlsUnlocked
        pasteButton.isEnabled = controlsUnlocked
        importButton.isEnabled = controlsUnlocked
        clearInputButton.isEnabled = controlsUnlocked
        targetAutoButton.isEnabled = controlsUnlocked
        targetToggleGroup.isEnabled = controlsUnlocked
        delaySlider.isEnabled = controlsUnlocked
        actionTimeoutSlider.isEnabled = controlsUnlocked
        startDelaySlider.isEnabled = controlsUnlocked
        startModeToggleGroup.isEnabled = controlsUnlocked
        startNowButton.isEnabled = controlsUnlocked
        startAfterButton.isEnabled = controlsUnlocked
        startClockButton.isEnabled = controlsUnlocked
        chooseDateButton.isEnabled = controlsUnlocked
        chooseClockButton.isEnabled = controlsUnlocked
        startAutomationButton.setText(
            when {
                scheduled -> R.string.aurora_start_scheduled_button
                running && paused -> R.string.autopilot_resume_button
                running -> R.string.autopilot_running_button
                canContinueQueuedBatch() -> R.string.autopilot_continue_next_batch
                app.preferences.startMode == AppPreferences.START_MODE_NOW -> R.string.swift_start_button
                else -> R.string.aurora_start_scheduled_button
            }
        )
        scheduleStatusPanel.visibility = if (scheduled) View.VISIBLE else View.GONE
        if (scheduled) {
            val remaining = (app.preferences.scheduledStartAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            scheduleStatusText.text = getString(
                R.string.aurora_schedule_at_format,
                DateFormat.getMediumDateFormat(this@MainActivity).format(Date(app.preferences.scheduledStartAtMillis)) +
                    " • " + DateFormat.getTimeFormat(this@MainActivity).format(Date(app.preferences.scheduledStartAtMillis)),
                formatRemainingTime(remaining)
            )
        }
        pauseAutomationButton.setText(
            if (paused) R.string.resume_automation else R.string.pause_automation
        )

        automationStageText.text = formatAutomationStage(app.preferences.automationStage)
        val baseDiagnostic = app.preferences.automationDiagnostic.ifBlank {
            getString(R.string.autopilot_waiting_for_start)
        }
        val health = RuntimeHealthMonitor.snapshot()
        val healthFresh = health != null &&
            SystemClock.elapsedRealtime() - health.updatedAtElapsedMs <= RUNTIME_HEALTH_FRESH_MS
        automationDiagnosticText.text = if (healthFresh && health != null) {
            val confidence = health.confidence?.let { "$it%" } ?: "—"
            val healthLine = getString(
                R.string.runtime_health_format,
                health.directive,
                health.action,
                confidence,
                health.stableScreenScans,
                health.watchdog
            )
            "$baseDiagnostic\n$healthLine"
        } else {
            baseDiagnostic
        }
        renderReadiness(serviceEnabled)
        renderInstalledTargets()
        renderTimingControls()
    }

    private fun renderInstalledTargets() {
        val profile = ProfileEnvironment.current(this)
        val personal = WhatsAppLauncher.canHandleInvite(this, WhatsAppLauncher.WHATSAPP_PACKAGE)
        val business = WhatsAppLauncher.canHandleInvite(this, WhatsAppLauncher.WHATSAPP_BUSINESS_PACKAGE)
        val cloned = WhatsAppLauncher.canHandleInvite(this, WhatsAppLauncher.WHATSAPP_CLONED_PACKAGE)
        val discovered = WhatsAppLauncher.discoverWhatsAppApps(this)
        val unlocked = !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
        val validation = WhatsAppLauncher.validateTarget(
            this,
            app.preferences.preferredTarget,
            app.preferences.selectedWhatsAppPackage
        )

        binding.targetAutoButton.isEnabled = unlocked && !profile.requiresExplicitAutoTarget
        binding.targetPersonalButton.isEnabled = unlocked && personal
        binding.targetBusinessButton.isEnabled = unlocked && business
        binding.targetCloneButton.isEnabled = unlocked && cloned
        binding.chooseInstalledWhatsAppButton.isEnabled = unlocked
        binding.testWhatsAppTargetButton.isEnabled = unlocked && validation.packageName != null
        binding.communityTraversalSwitch.isEnabled = unlocked
        binding.targetAutoButton.alpha = if (!profile.requiresExplicitAutoTarget) 1f else 0.45f
        binding.targetPersonalButton.alpha = if (personal) 1f else 0.45f
        binding.targetBusinessButton.alpha = if (business) 1f else 0.45f
        binding.targetCloneButton.alpha = if (cloned) 1f else 0.45f

        val selectedPackage = app.preferences.selectedWhatsAppPackage
        val selectedLabel = app.preferences.selectedWhatsAppLabel
        binding.selectedWhatsAppTargetText.text = when {
            !selectedPackage.isNullOrBlank() -> getString(
                R.string.selected_whatsapp_format,
                selectedLabel ?: selectedPackage,
                selectedPackage
            )
            profile.requiresExplicitAutoTarget -> getString(R.string.profile_explicit_target_required)
            else -> getString(R.string.selected_whatsapp_auto)
        }

        binding.profileSupportText.setText(
            when {
                profile.managedProfile -> R.string.profile_support_managed
                profile.isLikelySecureFolder -> R.string.profile_support_secure_samsung
                profile.secondaryProfile -> R.string.profile_support_secondary
                else -> R.string.profile_support_default
            }
        )

        binding.targetAppsStatusText.visibility = android.view.View.VISIBLE
        binding.targetAppsStatusText.text = if (validation.valid) {
            getString(R.string.profile_target_verified, validation.packageName ?: "")
        } else {
            getString(R.string.profile_target_not_verified)
        }
        binding.communityTraversalSwitch.isChecked = app.preferences.communityTraversalEnabled
        renderReadiness(isAnyAutomationEngineReady())
    }

    private fun showInstalledWhatsAppPicker() {
        val apps = WhatsAppLauncher.discoverWhatsAppApps(this, forceRefresh = true)
        if (apps.isEmpty()) {
            toast(R.string.no_whatsapp_apps_found)
            return
        }
        val profile = ProfileEnvironment.current(this)
        val adapter = WhatsAppTargetDialogAdapter(this, apps)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.whatsapp_picker_title)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.whatsapp_picker_auto) { _, _ ->
                if (profile.requiresExplicitAutoTarget) {
                    toast(R.string.profile_explicit_target_required)
                } else {
                    app.preferences.selectedWhatsAppPackage = null
                    app.preferences.selectedWhatsAppLabel = null
                    viewModel.setPreferredTarget(PreferredTarget.AUTO)
                    bindingTargetSelection = true
                    binding.targetToggleGroup.check(R.id.targetAutoButton)
                    bindingTargetSelection = false
                    renderInstalledTargets()
                }
            }
            .setAdapter(adapter) { shownDialog, which ->
                val target = apps[which]
                app.preferences.selectedWhatsAppPackage = target.packageName
                app.preferences.selectedWhatsAppLabel = target.label
                viewModel.setPreferredTarget(PreferredTarget.AUTO)
                bindingTargetSelection = true
                binding.targetToggleGroup.check(R.id.targetAutoButton)
                bindingTargetSelection = false
                renderInstalledTargets()
                shownDialog.dismiss()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).isEnabled =
                !profile.requiresExplicitAutoTarget
        }
        dialog.show()
    }

    private fun testSelectedWhatsAppTarget() {
        val validation = WhatsAppLauncher.validateTarget(
            this,
            app.preferences.preferredTarget,
            app.preferences.selectedWhatsAppPackage
        )
        val packageName = validation.packageName
        if (!validation.valid || packageName.isNullOrBlank()) {
            toast(
                if (validation.explicitTargetRequired) R.string.profile_explicit_target_required
                else R.string.target_test_failed
            )
            return
        }
        val opened = WhatsAppLauncher.testSelectedTarget(this, packageName)
        toast(if (opened) R.string.target_test_success else R.string.target_test_failed)
    }

    private fun lockValidatedRuntimeTarget(): Boolean {
        val validation = WhatsAppLauncher.validateTarget(
            this,
            app.preferences.preferredTarget,
            app.preferences.selectedWhatsAppPackage
        )
        val packageName = validation.packageName
        if (!validation.valid || packageName.isNullOrBlank()) {
            toast(
                if (validation.explicitTargetRequired) R.string.profile_explicit_target_required
                else R.string.autopilot_selected_app_missing
            )
            return false
        }
        app.preferences.lockRuntimeTarget(packageName, validation.profileKey)
        return true
    }

    private fun installedMark(installed: Boolean): String =
        getString(if (installed) R.string.autopilot_installed_mark else R.string.autopilot_missing_mark)

    private fun startAutomaticRun(allowQueuedContinuation: Boolean = false) {
        // 2.8.8 Fast Parity: explicit Start always means the proven 2.4.4-style fast JOIN path.
        // Keep semantic verification, but remove artificial inter-link waiting.
        app.preferences.runtimeShadowMode = false
        app.preferences.fastHandsFreeMode = true
        // Explicit hands-free runs must respect a manual Home/app switch. The engine pauses
        // instead of forcing WhatsApp back to foreground, then resumes only when the user returns
        // to the same locked WhatsApp target.
        app.preferences.autoPauseOutsideWhatsApp = true
        app.preferences.interLinkDelayMs = AutomationPolicy.FAST_INTER_LINK_DELAY_MS
        app.preferences.accessibilityActionTimeoutSeconds = AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS

        if (app.preferences.hasScheduledStart) {
            toast(R.string.aurora_schedule_already_active)
            return
        }

        if (app.preferences.accessibilityBatchRunning) {
            if (app.preferences.accessibilityPaused) toggleAutomationPause()
            else toast(R.string.autopilot_already_running)
            return
        }

        val queuedSnapshot = viewModel.state.value.snapshot
        if (allowQueuedContinuation && canContinueQueuedBatch() && queuedSnapshot != null) {
            if (!isSelectedTargetAvailable(app.preferences.preferredTarget)) {
                toast(
                    if (ProfileEnvironment.current(this).requiresExplicitAutoTarget &&
                        app.preferences.preferredTarget == PreferredTarget.AUTO &&
                        app.preferences.selectedWhatsAppPackage.isNullOrBlank()
                    ) R.string.profile_explicit_target_required else R.string.autopilot_selected_app_missing
                )
                return
            }
            val backend = resolveAutomationBackendForStart() ?: return
            if (backend == AutomationBackend.ACCESSIBILITY &&
                !AccessibilityStatus.isQuickJoinServiceEnabled(this@MainActivity)
            ) {
                pendingAutoStartAfterSettings = true
                pendingQueueContinuationAfterSettings = true
                app.preferences.accessibilityQuickJoin = true
                showOneTimeSetupDialog(true)
                return
            }
            app.preferences.runtimeAutomationBackend = backend
            app.preferences.accessibilityQuickJoin = backend == AutomationBackend.ACCESSIBILITY
            app.preferences.autoAdvance = true
            if (!lockValidatedRuntimeTarget()) return
            app.preferences.startAccessibilityBatch(queuedSnapshot.sessionId)
            val current = SessionRules.nextActionable(queuedSnapshot.links)
            if (backend == AutomationBackend.ACCESSIBILITY) {
                QuickJoinNotification.showAutomation(
                    context = this,
                    processedInBatch = 0,
                    currentLinkNumber = current?.position?.plus(1),
                    totalLinks = queuedSnapshot.stats.total,
                    delaySeconds = app.preferences.accessibilityJoinDelaySeconds,
                    paused = false
                )
            }
            toast(R.string.autopilot_next_batch_started)
            if (backend == AutomationBackend.SHIZUKU) {
                ShizukuAutomationService.start(this)
            } else {
                viewModel.openNextOrCurrent()
            }
            return
        }

        val rawText = binding.linksEditText.text?.toString().orEmpty()
        smartAutoStartJob?.cancel()
        if (rawText.isBlank()) {
            toast(R.string.enter_links_first)
            binding.linksEditText.requestFocus()
            return
        }

        if (!isSelectedTargetAvailable(app.preferences.preferredTarget)) {
            toast(
                if (ProfileEnvironment.current(this).requiresExplicitAutoTarget &&
                    app.preferences.preferredTarget == PreferredTarget.AUTO &&
                    app.preferences.selectedWhatsAppPackage.isNullOrBlank()
                ) R.string.profile_explicit_target_required else R.string.autopilot_selected_app_missing
            )
            return
        }

        if (app.preferences.startMode == AppPreferences.START_MODE_CLOCK &&
            requestedStartAtMillis() <= System.currentTimeMillis() + 1_000L
        ) {
            toast(R.string.quantum_schedule_past_error)
            return
        }

        val backend = resolveAutomationBackendForStart() ?: return
        if (backend == AutomationBackend.SHIZUKU && app.preferences.startMode != AppPreferences.START_MODE_NOW) {
            toast(R.string.shizuku_schedule_not_supported)
            return
        }
        if (backend == AutomationBackend.ACCESSIBILITY) {
            val readiness = AccessibilityStatus.readiness(this@MainActivity)
            if (!readiness.systemEnabled || !readiness.localServiceConnected) {
                pendingAutoStartAfterSettings = true
                pendingQueueContinuationAfterSettings = false
                app.preferences.accessibilityQuickJoin = true
                waitForLocalAccessibilityBind(allowQueuedContinuation = false, startAfterBind = true)
                return
            }
        }

        app.preferences.runtimeAutomationBackend = backend
        app.preferences.accessibilityQuickJoin = backend == AutomationBackend.ACCESSIBILITY
        app.preferences.autoAdvance = true
        app.preferences.stopAccessibilityBatch(
            AutomationStopReason.USER_STOPPED,
            "Preparing a new Autopilot session"
        )
        QuickJoinNotification.cancel(this)

        lastAutomaticInputHash = normalizedInputHash(rawText)
        viewModel.prepareAutomaticRun(
            rawText = rawText,
            source = LinkSource.PASTE,
            sourceLabel = getString(R.string.source_manual)
        )
    }

    private fun resolveAutomationBackendForStart(): AutomationBackend? {
        val requested = app.preferences.automationBackend
        val snapshot = NativeProfileEngineRouter.inspect(this, requested)
        val decision = snapshot.decision

        // Explicit Accessibility keeps the existing one-time setup flow in MainActivity.
        if (decision.backend == AutomationBackend.ACCESSIBILITY &&
            decision.setupAction == NativeEngineSetupAction.ENABLE_LOCAL_ACCESSIBILITY
        ) {
            return AutomationBackend.ACCESSIBILITY
        }

        if (decision.runnable) {
            if (decision.backend == AutomationBackend.SHIZUKU &&
                requested != AutomationBackend.SHIZUKU
            ) {
                Toast.makeText(
                    this,
                    "المحرك المختار تلقائيًا: Shizuku السريع",
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (requested == AutomationBackend.SHIZUKU && decision.backend == AutomationBackend.ACCESSIBILITY) {
                Toast.makeText(
                    this,
                    "Shizuku غير جاهز؛ سيتم استخدام Accessibility المحلية داخل هذا الملف لهذه الجولة",
                    Toast.LENGTH_LONG
                ).show()
            }
            return decision.backend
        }

        val message = when (decision.setupAction) {
            NativeEngineSetupAction.APPLY_WORK_ACCESSIBILITY_POLICY -> R.string.native_engine_apply_work_policy
            NativeEngineSetupAction.START_OR_AUTHORIZE_SHIZUKU -> R.string.native_engine_start_shizuku
            NativeEngineSetupAction.ENABLE_LOCAL_ACCESSIBILITY -> R.string.native_engine_open_accessibility
            NativeEngineSetupAction.BLOCKED_BY_PROFILE_POLICY -> R.string.native_engine_blocked
            NativeEngineSetupAction.NONE -> R.string.native_engine_blocked
        }
        toast(message)
        startActivity(Intent(this, SettingsActivity::class.java))
        return null
    }

    private fun canContinueQueuedBatch(): Boolean {
        val snapshot = viewModel.state.value.snapshot ?: return false
        val resumableReason = app.preferences.automationStopReason in setOf(
            AutomationStopReason.BATCH_LIMIT_REACHED,
            AutomationStopReason.USER_STOPPED,
            AutomationStopReason.SERVICE_DISABLED,
            AutomationStopReason.TARGET_UNSUPPORTED,
            AutomationStopReason.OPEN_FAILED,
            AutomationStopReason.BROWSER_FALLBACK,
            AutomationStopReason.UNKNOWN_SCREEN,
            AutomationStopReason.ACTION_TIMEOUT,
            AutomationStopReason.RUNTIME_CIRCUIT_BREAKER
        )
        return !app.preferences.accessibilityBatchRunning &&
            !app.preferences.hasScheduledStart &&
            resumableReason &&
            snapshot.stats.remaining > 0
    }

    private fun isSelectedTargetAvailable(target: PreferredTarget): Boolean =
        WhatsAppLauncher.validateTarget(
            this,
            target,
            app.preferences.selectedWhatsAppPackage
        ).valid

    private fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.Message -> Toast.makeText(
                this,
                getString(event.messageRes, *event.args.toTypedArray()),
                Toast.LENGTH_LONG
            ).show()

            is MainEvent.AutomaticSessionReady -> {
                val snapshot = viewModel.state.value.snapshot
                GroupJoinerResultStore.sync(this, snapshot)
                val requestedStartAt = requestedStartAtMillis()
                if (requestedStartAt > System.currentTimeMillis() + 1_000L) {
                    app.preferences.scheduleAccessibilityBatch(event.sessionId, requestedStartAt)
                    QuickJoinNotification.showScheduled(
                        context = this,
                        startAtMillis = requestedStartAt,
                        delaySeconds = app.preferences.accessibilityJoinDelaySeconds
                    )
                    toast(R.string.aurora_schedule_created)
                    renderRuntimeState()
                } else {
                    if (!lockValidatedRuntimeTarget()) return
                    app.preferences.startAccessibilityBatch(event.sessionId)
                    if (app.preferences.runtimeAutomationBackend == AutomationBackend.ACCESSIBILITY) {
                        QuickJoinNotification.showAutomation(
                            context = this,
                            processedInBatch = 0,
                            currentLinkNumber = 1,
                            totalLinks = event.totalLinks,
                            delaySeconds = app.preferences.accessibilityJoinDelaySeconds,
                            paused = false
                        )
                    }
                    if (app.preferences.runtimeAutomationBackend == AutomationBackend.SHIZUKU) {
                        ShizukuAutomationService.start(this)
                    } else {
                        viewModel.openNextOrCurrent()
                    }
                }
            }

            is MainEvent.OpenLink -> openInvitationForAutomation(event)

            is MainEvent.ExportCsv -> {
                pendingExportContent = event.content
                createReportLauncher.launch(event.fileName)
            }

            is MainEvent.ShareText -> startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.content)
                    },
                    getString(R.string.share_report)
                )
            )
        }
    }

    private fun openInvitationForAutomation(event: MainEvent.OpenLink) {
        if (app.preferences.runtimeLockedWhatsAppPackage.isNullOrBlank() && !lockValidatedRuntimeTarget()) return
        val destination = WhatsAppLauncher.launch(
            this,
            event.url,
            event.preferredTarget,
            app.preferences.runtimeLockedWhatsAppPackage ?: app.preferences.selectedWhatsAppPackage,
            strictProfileTarget = app.preferences.strictProfileTargeting,
            expectedProfileKey = app.preferences.runtimeLockedProfileKey
        )
        val supported = destination == LaunchDestination.PERSONAL ||
            destination == LaunchDestination.BUSINESS ||
            destination == LaunchDestination.CLONED ||
            destination == LaunchDestination.SELECTED ||
            destination == LaunchDestination.DUAL_CHOOSER

        if (supported) {
            app.preferences.markAutomationLaunched()
            if (app.preferences.runtimeAutomationBackend == AutomationBackend.ACCESSIBILITY) {
                QuickJoinAccessibilityService.requestImmediateScan()
            }
            viewModel.onLaunchResult(event.linkId, success = true, browserFallback = false)
            return
        }

        app.preferences.accessibilityProcessedCount += 1
        viewModel.onLaunchResult(
            linkId = event.linkId,
            success = destination != LaunchDestination.NONE,
            browserFallback = destination == LaunchDestination.BROWSER
        )

        lifecycleScope.launch {
            delay(1_000L)
            if (app.preferences.accessibilityBatchRunning &&
                app.preferences.accessibilityProcessedCount < AutomationPolicy.BATCH_SIZE
            ) {
                viewModel.openNextOrCurrent()
            } else {
                app.preferences.completeAccessibilityBatch(
                    AutomationStopReason.SESSION_COMPLETE,
                    "No more links can be opened"
                )
            }
        }
    }

    private fun toggleAutomationPause() {
        if (!app.preferences.accessibilityBatchRunning) return
        if (app.preferences.accessibilityPaused) {
            app.preferences.resumeAccessibilityBatch()
            toast(R.string.automation_resumed)
        } else {
            app.preferences.pauseAccessibilityBatch()
            toast(R.string.automation_paused)
        }
        renderRuntimeState()
        refreshAutomationNotification()
    }

    private fun refreshAutomationNotification() {
        if (!app.preferences.accessibilityBatchRunning) return
        val snapshot = viewModel.state.value.snapshot ?: return
        val current = SessionRules.currentOpened(snapshot.links)
            ?: SessionRules.nextActionable(snapshot.links)
        QuickJoinNotification.showAutomation(
            context = this,
            processedInBatch = app.preferences.accessibilityProcessedCount,
            currentLinkNumber = current?.position?.plus(1),
            totalLinks = snapshot.stats.total,
            delaySeconds = app.preferences.accessibilityJoinDelaySeconds,
            paused = app.preferences.accessibilityPaused
        )
    }

    private fun stopAutomationFromUi() {
        app.preferences.clearScheduledStart()
        app.preferences.stopAccessibilityBatch(
            AutomationStopReason.USER_STOPPED,
            "Stopped from Autopilot dashboard"
        )
        GroupJoinerResultStore.sync(this, viewModel.state.value.snapshot)
        QuickJoinNotification.cancel(this)
        toast(R.string.automation_stopped)
        viewModel.refresh()
    }

    private fun cancelScheduledStart() {
        if (!app.preferences.hasScheduledStart) return
        app.preferences.clearScheduledStart()
        app.preferences.resetAccessibilityRun(viewModel.state.value.snapshot?.sessionId)
        QuickJoinNotification.cancel(this)
        toast(R.string.aurora_schedule_cancelled)
        renderRuntimeState()
    }

    private fun waitForLocalAccessibilityBind(
        allowQueuedContinuation: Boolean,
        startAfterBind: Boolean
    ) {
        accessibilityReconnectJob?.cancel()
        accessibilityReconnectJob = lifecycleScope.launch {
            val deadline = SystemClock.elapsedRealtime() + ProfileControlPolicy.ACCESSIBILITY_RECONNECT_WAIT_MS
            var sawSystemEnabled = false
            var consecutiveDisabledReads = 0

            while (SystemClock.elapsedRealtime() < deadline) {
                val readiness = AccessibilityStatus.readiness(this@MainActivity)
                if (readiness.systemEnabled) {
                    sawSystemEnabled = true
                    consecutiveDisabledReads = 0
                    if (readiness.localServiceConnected) {
                        renderRuntimeState()
                        if (startAfterBind && pendingAutoStartAfterSettings) {
                            pendingAutoStartAfterSettings = false
                            pendingQueueContinuationAfterSettings = false
                            startAutomaticRun(allowQueuedContinuation = allowQueuedContinuation)
                        }
                        return@launch
                    }
                } else {
                    consecutiveDisabledReads += 1
                    if (ProfileControlPolicy.shouldPromptAccessibilitySetup(
                            sawSystemEnabledDuringWait = sawSystemEnabled,
                            consecutiveDisabledReads = consecutiveDisabledReads
                        )
                    ) {
                        renderRuntimeState()
                        if (startAfterBind) showOneTimeSetupDialog(true)
                        return@launch
                    }
                }
                delay(ProfileControlPolicy.ACCESSIBILITY_RECONNECT_POLL_MS)
            }

            renderRuntimeState()
            if (startAfterBind && !sawSystemEnabled) {
                showOneTimeSetupDialog(true)
            } else if (sawSystemEnabled) {
                // Android can keep the toggle/name while never binding the freshly installed APK.
                // The only supported recovery is the exact service page so the user can turn this
                // instance off/on once; preserve the pending run and resume after the live callback.
                toast(R.string.accessibility_enabled_but_not_bound)
                showOneTimeSetupDialog(startAfterBind)
            }
        }
    }

    private fun showOneTimeSetupDialog(startAfterSetup: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.autopilot_setup_dialog_title)
            .setMessage(R.string.autopilot_setup_dialog_message)
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (startAfterSetup) {
                    pendingAutoStartAfterSettings = false
                    pendingQueueContinuationAfterSettings = false
                }
            }
            .setNeutralButton(R.string.autopilot_open_app_info) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .setPositiveButton(R.string.open_accessibility_settings) { _, _ ->
                openAccessibilitySettings()
            }
            .show()
    }

    private fun openAccessibilitySettings() {
        AccessibilitySettingsLauncher.open(this)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        if (text.isBlank()) {
            toast(R.string.clipboard_empty)
            return
        }
        binding.linksEditText.setText(text)
        binding.linksEditText.setSelection(binding.linksEditText.text?.length ?: 0)
    }

    private fun importDocuments(uris: List<Uri>) {
        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            val merged = withContext(Dispatchers.IO) {
                uris.take(MAX_IMPORT_FILES).mapNotNull { uri ->
                    DocumentIO.readText(contentResolver, uri, MAX_DOCUMENT_CHARS).getOrNull()
                }.filter { it.isNotBlank() }.joinToString("\n").take(MAX_DOCUMENT_CHARS)
            }
            binding.loadingIndicator.visibility = View.GONE
            if (merged.isBlank()) {
                toast(R.string.import_failed)
            } else {
                binding.linksEditText.setText(merged)
                binding.linksEditText.setSelection(binding.linksEditText.text?.length ?: 0)
                toast(R.string.autopilot_files_imported)
            }
        }
    }

    private fun scheduleLinkAnalysis(text: String) {
        linkAnalysisJob?.cancel()
        if (text.isBlank()) {
            applyDetectedLinkCount(0)
            return
        }
        linkAnalysisJob = lifecycleScope.launch {
            delay(INPUT_ANALYSIS_DEBOUNCE_MS)
            val count = withContext(Dispatchers.Default) {
                INVITE_REGEX.findAll(text)
                    .map { it.value.lowercase() }
                    .distinct()
                    .take(AutomationPolicy.MAX_LINKS_PER_SESSION + 1)
                    .count()
            }
            if (binding.linksEditText.text?.toString() == text) {
                applyDetectedLinkCount(count.coerceAtMost(AutomationPolicy.MAX_LINKS_PER_SESSION))
            }
        }
    }

    private fun applyDetectedLinkCount(count: Int) {
        detectedLinkCount = count.coerceIn(0, AutomationPolicy.MAX_LINKS_PER_SESSION)
        binding.linkCountText.text = getString(
            R.string.autopilot_detected_links_format,
            detectedLinkCount,
            AutomationPolicy.MAX_LINKS_PER_SESSION
        )
        updateSessionEstimate()
        renderReadiness(isAnyAutomationEngineReady())
        if (detectedLinkCount > 0) scheduleSmartAutoStart()
    }

    private fun uiLinkWindow(snapshot: com.althmany.groupmanager.model.SessionSnapshot): List<GroupLink> {
        val links = snapshot.links
        if (links.size <= UI_LINK_WINDOW_SIZE) return links
        val anchor = (SessionRules.currentOpened(links) ?: SessionRules.nextActionable(links))?.position ?: 0
        val half = UI_LINK_WINDOW_SIZE / 2
        val start = (anchor - half).coerceIn(0, (links.size - UI_LINK_WINDOW_SIZE).coerceAtLeast(0))
        return links.subList(start, (start + UI_LINK_WINDOW_SIZE).coerceAtMost(links.size))
    }

    private fun copyLink(link: GroupLink) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.group_link_label), link.url))
        toast(R.string.link_copied)
    }

    private fun confirmDeleteLink(link: GroupLink) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_link_title)
            .setMessage(R.string.delete_link_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteLink(link.id) }
            .show()
    }

    private fun confirmClearSession() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_session_title)
            .setMessage(R.string.clear_session_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                stopAutomationFromUi()
                viewModel.clearCurrentSession()
            }
            .show()
    }

    private fun handleControlIntent(intent: Intent?) {
        val finishReason = intent?.getStringExtra("automation_finish_reason")
        if (!finishReason.isNullOrBlank()) {
            lastAutomaticInputHash = null
            pendingAutoStartAfterSettings = false
            pendingQueueContinuationAfterSettings = false
            accessibilityReconnectJob?.cancel()
            intent.removeExtra("automation_finish_reason")
        }

        when (intent?.action) {
            QuickJoinNotification.ACTION_STOP_AUTOMATION -> stopAutomationFromUi()
            QuickJoinNotification.ACTION_TOGGLE_PAUSE_AUTOMATION -> toggleAutomationPause()
            QuickJoinNotification.ACTION_SKIP_NEXT -> viewModel.markCurrentSkipped()
            QuickJoinNotification.ACTION_REOPEN -> viewModel.openNextOrCurrent()
        }
        if (intent?.action?.startsWith("com.althmany.groupmanager.action.") == true) {
            intent.action = Intent.ACTION_MAIN
        }
    }

    private fun renderReadiness(serviceEnabled: Boolean) {
        val hasLinks = detectedLinkCount > 0
        val targetReady = isSelectedTargetAvailable(app.preferences.preferredTarget)
        val readyCount = listOf(serviceEnabled, hasLinks, targetReady).count { it }
        binding.readinessSummaryText.setText(
            when (readyCount) {
                3 -> R.string.autopilot_readiness_ready
                2 -> R.string.autopilot_readiness_partial
                else -> R.string.autopilot_readiness_waiting
            }
        )
        binding.readinessSummaryText.setTextColor(
            ContextCompat.getColor(
                this,
                if (readyCount == 3) R.color.status_joined else R.color.toolbar_subtitle
            )
        )
        binding.readinessProgress.progress = when (readyCount) {
            3 -> 100
            2 -> 67
            1 -> 34
            else -> 0
        }
    }

    private fun applyFastHandsFreePreset() = with(binding) {
        app.preferences.interLinkDelayMs = AutomationPolicy.FAST_INTER_LINK_DELAY_MS
        app.preferences.accessibilityActionTimeoutSeconds = AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS
        delaySlider.value = AutomationPolicy.FAST_INTER_LINK_DELAY_MS.toFloat()
        actionTimeoutSlider.value = AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS.toFloat()
        delayValueText.text = formatInterLinkDelay(AutomationPolicy.FAST_INTER_LINK_DELAY_MS)
        actionTimeoutValueText.text = getString(
            R.string.nebula_action_timeout_format,
            AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS
        )
    }

    private fun scheduleSmartAutoStart() {
        smartAutoStartJob?.cancel()
        if (!app.preferences.smartAutoStart) return
        if (app.preferences.accessibilityBatchRunning || app.preferences.hasScheduledStart || viewModel.state.value.isLoading) return
        if (detectedLinkCount <= 0) return
        val rawText = binding.linksEditText.text?.toString().orEmpty()
        if (!isSelectedTargetAvailable(app.preferences.preferredTarget)) return
        val hash = normalizedInputHash(rawText)
        if (lastAutomaticInputHash == hash) return

        smartAutoStartJob = lifecycleScope.launch {
            for (second in AutomationPolicy.SMART_START_COUNTDOWN_SECONDS downTo 1) {
                val latest = binding.linksEditText.text?.toString().orEmpty()
                if (!app.preferences.smartAutoStart || normalizedInputHash(latest) != hash) {
                    binding.autoStartHintText.setText(R.string.autopilot_auto_start_hint)
                    return@launch
                }
                binding.autoStartHintText.text = getString(
                    R.string.nebula_auto_start_countdown,
                    second
                )
                delay(1_000L)
            }
            binding.autoStartHintText.setText(R.string.autopilot_auto_start_hint)
            startAutomaticRun(allowQueuedContinuation = false)
        }
    }

    private fun updateSessionEstimate() {
        val seconds = AutomationPolicy.estimatedSessionSecondsFromMillis(
            linkCount = detectedLinkCount,
            delayMs = app.preferences.interLinkDelayMs,
            actionTimeoutSeconds = app.preferences.accessibilityActionTimeoutSeconds
        )
        binding.sessionEstimateText.text = if (seconds == 0) {
            getString(R.string.nebula_estimate_empty)
        } else {
            getString(
                R.string.nebula_estimate_format,
                (seconds + 59) / 60
            )
        }
    }

    private fun formatInterLinkDelay(milliseconds: Int): String {
        val safe = AutomationPolicy.clampInterLinkDelayMs(milliseconds)
        return when {
            safe == 0 -> getString(R.string.speed_instant_value)
            safe < 1_000 -> getString(R.string.speed_milliseconds_value, safe)
            safe % 1_000 == 0 -> getString(R.string.aurora_delay_seconds_format, safe / 1_000)
            else -> getString(R.string.speed_seconds_decimal_value, safe / 1_000f)
        }
    }

    private fun normalizedInputHash(text: String): Int = text.trim().hashCode()

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (shared.isNotBlank()) {
            binding.linksEditText.setText(shared)
            binding.linksEditText.setSelection(binding.linksEditText.text?.length ?: 0)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !app.preferences.notificationPermissionAsked
        ) {
            app.preferences.notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun formatAutomationStage(stage: AutomationStage): String = getString(
        when (stage) {
            AutomationStage.IDLE -> R.string.automation_stage_idle
            AutomationStage.SCHEDULED -> R.string.automation_stage_scheduled
            AutomationStage.OPENING_LINK -> R.string.automation_stage_opening
            AutomationStage.WAITING_FOR_WHATSAPP -> R.string.automation_stage_waiting_whatsapp
            AutomationStage.LOOKING_FOR_PREVIEW -> R.string.automation_stage_looking_preview
            AutomationStage.LOOKING_FOR_JOIN -> R.string.automation_stage_looking_join
            AutomationStage.VERIFYING_RESULT -> R.string.automation_stage_verifying
            AutomationStage.WAITING_BEFORE_NEXT -> R.string.automation_stage_waiting_next
            AutomationStage.PAUSED -> R.string.automation_stage_paused
            AutomationStage.COMPLETED -> R.string.automation_stage_completed
            AutomationStage.STOPPED -> R.string.automation_stage_stopped
        }
    )

    private fun formatRemainingTime(milliseconds: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L -> "$hours س $minutes د"
            totalMinutes > 0L -> "$totalMinutes د"
            else -> "أقل من دقيقة"
        }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val RUNTIME_HEALTH_FRESH_MS = 5_000L
        private const val UI_LINK_WINDOW_SIZE = 120
        private const val INPUT_ANALYSIS_DEBOUNCE_MS = 180L
        private const val MAX_DOCUMENT_CHARS = 16_000_000
        private const val MAX_IMPORT_FILES = 20
        private const val SMART_AUTO_START_DEBOUNCE_MS = 3_000L
        private val INVITE_REGEX = Regex(
            "https?://(?:chat\\.)?whatsapp\\.com/[A-Za-z0-9_-]+",
            RegexOption.IGNORE_CASE
        )
    }
}
