package com.althmany.groupmanager.ui

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.althmany.groupmanager.GroupManagerApp
import com.althmany.groupmanager.R
import com.althmany.groupmanager.data.AppPreferences
import com.althmany.groupmanager.data.GroupLinkRepository
import com.althmany.groupmanager.domain.ActionThrottle
import com.althmany.groupmanager.domain.SessionRules
import com.althmany.groupmanager.domain.WhatsAppLinkParser
import com.althmany.groupmanager.model.LinkResultCode
import com.althmany.groupmanager.model.LinkSource
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.model.ParseReport
import com.althmany.groupmanager.model.PreferredTarget
import com.althmany.groupmanager.model.SessionSnapshot
import com.althmany.groupmanager.util.SessionReportFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** UI state for the single-screen Autopilot dashboard. */
data class MainUiState(
    val isLoading: Boolean = true,
    val snapshot: SessionSnapshot? = null,
    val parseReport: ParseReport? = null,
    val preferredTarget: PreferredTarget = PreferredTarget.AUTO
)

sealed interface MainEvent {
    data class Message(
        @StringRes val messageRes: Int,
        val args: List<Any> = emptyList()
    ) : MainEvent

    /** Activity starts the Accessibility batch before opening the first link. */
    data class AutomaticSessionReady(
        val sessionId: String,
        val totalLinks: Int
    ) : MainEvent

    data class OpenLink(
        val linkId: Long,
        val url: String,
        val preferredTarget: PreferredTarget
    ) : MainEvent

    data class ExportCsv(
        val fileName: String,
        val content: String
    ) : MainEvent

    data class ShareText(val content: String) : MainEvent
}

class MainViewModel(
    private val repository: GroupLinkRepository,
    private val preferences: AppPreferences
) : ViewModel() {
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val operationMutex = Mutex()
    private val launchThrottle = ActionThrottle(MINIMUM_LAUNCH_INTERVAL_MS)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            operationMutex.withLock {
                val result = withContext(Dispatchers.IO) {
                    runCatching { repository.loadActiveDashboardSnapshot() }
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    snapshot = result.getOrNull() ?: _state.value.snapshot,
                    preferredTarget = preferences.preferredTarget
                )
                if (result.isFailure) {
                    _events.send(MainEvent.Message(R.string.operation_failed))
                }
            }
        }
    }

    /**
     * One-tap workflow: parse, normalize, cap at the configured large disk-backed queue limit, replace the current
     * session and signal the Activity to start Accessibility automation.
     */
    fun prepareAutomaticRun(
        rawText: String,
        source: LinkSource,
        sourceLabel: String
    ) {
        if (rawText.isBlank()) {
            emitMessage(R.string.enter_links_first)
            return
        }

        viewModelScope.launch {
            operationMutex.withLock {
                _state.value = _state.value.copy(isLoading = true)
                val report = withContext(Dispatchers.Default) {
                    WhatsAppLinkParser.extract(rawText)
                }

                if (report.accepted.isEmpty()) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        parseReport = report
                    )
                    _events.send(MainEvent.Message(R.string.no_valid_links))
                    return@withLock
                }

                val snapshotResult = withContext(Dispatchers.IO) {
                    runCatching {
                        repository.createSession(
                            report = report,
                            source = source,
                            sourceLabel = sourceLabel
                        )
                    }
                }
                val snapshot = snapshotResult.getOrNull()
                if (snapshot == null) {
                    _state.value = _state.value.copy(isLoading = false)
                    _events.send(MainEvent.Message(R.string.operation_failed))
                    return@withLock
                }

                launchThrottle.reset()
                _state.value = _state.value.copy(
                    isLoading = false,
                    snapshot = snapshot,
                    parseReport = report,
                    preferredTarget = preferences.preferredTarget
                )
                _events.send(
                    MainEvent.Message(
                        R.string.autopilot_session_ready,
                        listOf(
                            report.accepted.size,
                            report.duplicateCount,
                            report.invalidCount,
                            report.ignoredBecauseOfLimit
                        )
                    )
                )
                _events.send(
                    MainEvent.AutomaticSessionReady(
                        sessionId = snapshot.sessionId,
                        totalLinks = snapshot.stats.total
                    )
                )
            }
        }
    }

    fun setPreferredTarget(target: PreferredTarget) {
        preferences.preferredTarget = target
        _state.value = _state.value.copy(preferredTarget = target)
    }

    fun openNextOrCurrent() {
        viewModelScope.launch {
            operationMutex.withLock { openNextOrCurrentInternal() }
        }
    }

    fun reopen(linkId: Long) {
        viewModelScope.launch {
            operationMutex.withLock { prepareAndOpen(linkId) }
        }
    }

    /** Used only as a manual recovery action from the dashboard. */
    fun markCurrentSkipped() {
        viewModelScope.launch {
            operationMutex.withLock {
                val current = _state.value.snapshot?.links?.let(SessionRules::currentOpened)
                if (current == null) {
                    _events.send(MainEvent.Message(R.string.open_link_first))
                    return@withLock
                }
                withContext(Dispatchers.IO) {
                    repository.markStatus(
                        current.id,
                        LinkStatus.SKIPPED,
                        LinkResultCode.USER_SKIPPED,
                        "Skipped manually from Autopilot dashboard"
                    )
                }
                val refreshed = withContext(Dispatchers.IO) { repository.loadActiveDashboardSnapshot() }
                _state.value = _state.value.copy(snapshot = refreshed)
                launchThrottle.reset()
                openNextOrCurrentInternal()
            }
        }
    }

    fun onLaunchResult(
        linkId: Long,
        success: Boolean,
        browserFallback: Boolean
    ) {
        viewModelScope.launch {
            operationMutex.withLock {
                when {
                    !success -> {
                        withContext(Dispatchers.IO) {
                            repository.markStatus(
                                linkId,
                                LinkStatus.FAILED,
                                LinkResultCode.OPEN_FAILED,
                                "No selected WhatsApp installation could open the invitation"
                            )
                        }
                    }
                    browserFallback -> {
                        withContext(Dispatchers.IO) {
                            repository.markStatus(
                                linkId,
                                LinkStatus.FAILED,
                                LinkResultCode.BROWSER_FALLBACK,
                                "Invitation opened outside the selected WhatsApp application"
                            )
                        }
                    }
                    else -> Unit
                }
                if (!success || browserFallback) {
                    val refreshed = withContext(Dispatchers.IO) { repository.loadActiveDashboardSnapshot() }
                    _state.value = _state.value.copy(snapshot = refreshed)
                }
            }
        }
    }

    fun deleteLink(linkId: Long) {
        viewModelScope.launch {
            operationMutex.withLock {
                withContext(Dispatchers.IO) { repository.deleteLink(linkId) }
                val refreshed = withContext(Dispatchers.IO) { repository.loadActiveDashboardSnapshot() }
                if (refreshed?.stats?.total == 0) {
                    withContext(Dispatchers.IO) { repository.clearCurrentSession() }
                    _state.value = _state.value.copy(snapshot = null)
                } else {
                    _state.value = _state.value.copy(snapshot = refreshed)
                }
            }
        }
    }

    fun clearCurrentSession() {
        viewModelScope.launch {
            operationMutex.withLock {
                withContext(Dispatchers.IO) { repository.clearCurrentSession() }
                launchThrottle.reset()
                _state.value = _state.value.copy(snapshot = null, parseReport = null)
                _events.send(MainEvent.Message(R.string.session_cleared))
            }
        }
    }

    fun exportCsv() {
        if (_state.value.snapshot == null) {
            emitMessage(R.string.no_session_to_export)
            return
        }
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { repository.loadActiveSnapshot() }
            if (snapshot == null) {
                _events.send(MainEvent.Message(R.string.no_session_to_export))
                return@launch
            }
            val csv = withContext(Dispatchers.Default) { SessionReportFormatter.toCsv(snapshot) }
            _events.send(
                MainEvent.ExportCsv(
                    fileName = SessionReportFormatter.suggestedFileName(),
                    content = csv
                )
            )
        }
    }

    fun shareReport() {
        if (_state.value.snapshot == null) {
            emitMessage(R.string.no_session_to_export)
            return
        }
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { repository.loadActiveSnapshot() }
            if (snapshot == null) {
                _events.send(MainEvent.Message(R.string.no_session_to_export))
                return@launch
            }
            val text = withContext(Dispatchers.Default) { SessionReportFormatter.toShareText(snapshot) }
            _events.send(MainEvent.ShareText(text))
        }
    }

    private suspend fun openNextOrCurrentInternal() {
        val sessionId = preferences.activeSessionId
        if (sessionId == null) {
            _events.send(MainEvent.Message(R.string.extract_before_start))
            return
        }
        val target = withContext(Dispatchers.IO) { repository.loadAutomationNext(sessionId) }
        if (target == null) {
            _state.value = _state.value.copy(
                snapshot = withContext(Dispatchers.IO) { repository.loadActiveDashboardSnapshot() }
            )
            _events.send(MainEvent.Message(R.string.session_complete))
            return
        }
        prepareAndOpen(target.id)
    }

    private suspend fun prepareAndOpen(linkId: Long) {
        if (!launchThrottle.tryAcquire(SystemClock.elapsedRealtime())) {
            return
        }

        val opened = withContext(Dispatchers.IO) { repository.markOpened(linkId) }
        if (opened == null) {
            launchThrottle.reset()
            _events.send(MainEvent.Message(R.string.link_not_found))
            return
        }

        val refreshed = withContext(Dispatchers.IO) { repository.loadActiveDashboardSnapshot() }
        _state.value = _state.value.copy(snapshot = refreshed)
        _events.send(
            MainEvent.OpenLink(
                linkId = opened.id,
                url = opened.url,
                preferredTarget = preferences.preferredTarget
            )
        )
    }

    private fun emitMessage(@StringRes messageRes: Int, vararg args: Any) {
        viewModelScope.launch {
            _events.send(MainEvent.Message(messageRes, args.toList()))
        }
    }

    class Factory(private val app: GroupManagerApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(app.repository, app.preferences) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    companion object {
        private const val MINIMUM_LAUNCH_INTERVAL_MS = 700L
    }
}
