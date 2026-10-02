package com.humblesolutions.finai.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorDashboardRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.DashboardRepository
import com.humblesolutions.finai.usecase.DashboardMonths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the dashboard (PRD F3): one month at a time, and moving between them.
 *
 * Which month comes before this one, and every figure on the screen, are
 * decided elsewhere — [DashboardMonths] and the server. This model moves
 * answers in and out.
 *
 * Nothing is saved across process death. The screen is a read of the server
 * with no draft in it, and re-reading is both cheap and more honest than
 * restoring figures that may have changed.
 */
class DashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var repositories: DashboardRepositories? = null
    private var boundTo: String? = null

    /**
     * Rises on every bind and every month change, so a slow answer for the
     * month the user has already left cannot land on top of the one they are
     * looking at.
     */
    private var generation = 0

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            DashboardRepositories(
                dashboard = KtorDashboardRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> DashboardRepositories?) {
        if (userId.isBlank() || userId == boundTo) return
        repositories?.close()
        generation++
        boundTo = userId
        _uiState.value = DashboardUiState()
        repositories = build() ?: return
        load()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    /**
     * @param refresh re-reading with something already on screen. The figures
     *   stay up and a failure keeps them, rather than blanking a month that
     *   was read successfully a moment ago.
     */
    fun load(refresh: Boolean = false) {
        val repos = repositories ?: return
        val started = ++generation
        val month = _uiState.value.month
        _uiState.update {
            it.copy(loading = !refresh, refreshing = refresh, loadFailed = false, errorKey = null)
        }
        viewModelScope.launch {
            try {
                val data = repos.dashboard.read(DashboardMonths.wire(month))
                val locale = orNull { repos.capabilities.fetch() }?.locale.orEmpty()
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        data = data,
                        locale = locale.ifBlank { it.locale },
                        loading = false,
                        refreshing = false,
                        loadFailed = false,
                        errorKey = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // A refresh that fails keeps what it had; a first load
                        // has nothing to keep and must say so.
                        loadFailed = !refresh,
                        errorKey = e.messageKey,
                    )
                }
            }
        }
    }

    /** Step to the month before the one shown. */
    fun showPreviousMonth() = show(DashboardMonths.previous(_uiState.value.month))

    /** Step forward, which does nothing on the month that is running. */
    fun showNextMonth() {
        val state = _uiState.value
        if (!DashboardMonths.canGoForward(state.month)) return
        show(DashboardMonths.next(state.month))
    }

    private fun show(month: LocalDate) {
        if (month == _uiState.value.month) return
        _uiState.update { it.copy(month = month) }
        // A refresh, not a load: the month label changes at once and the old
        // figures stay until the new ones arrive, rather than the screen
        // emptying on every step.
        load(refresh = true)
    }

    private suspend fun <T> orNull(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }
}

internal class DashboardRepositories(
    val dashboard: DashboardRepository,
    val capabilities: CapabilitiesRepository,
) {
    fun close() {
        dashboard.close()
        capabilities.close()
    }
}
