package com.humblesolutions.finai.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.repository.CapabilitiesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Which features the bottom bar may offer (PRD §4.6).
 *
 * The capabilities payload decides what a client *shows*; the API decides
 * independently what it *allows*, so a tab hidden here is a courtesy, not a
 * lock (CLAUDE.md → Region & feature gating).
 *
 * This sits in navigation rather than in a screen's model because a tab has
 * to be absent before anything behind it is opened — by the time the Budget
 * screen could ask, its tab is already drawn.
 *
 * **Null means not known yet.** The bar starts without the gated tabs and
 * gains them when the answer arrives, because appearing is less jarring than
 * a tab vanishing under a thumb — and because a feature wrongly shown would
 * be a 403 the person did not ask for.
 */
class FeaturesViewModel : ViewModel() {

    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

    private var repository: CapabilitiesRepository? = null
    private var boundTo: String? = null

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { KtorCapabilitiesRepository(ApiConfig.BASE_URL, SupabaseTokenSource(it), logging) }
    }

    internal fun bind(userId: String, build: () -> CapabilitiesRepository?) {
        if (userId.isBlank() || userId == boundTo) return
        repository?.close()
        boundTo = userId
        _capabilities.value = null
        repository = build() ?: return
        load()
    }

    private fun load() {
        val repo = repository ?: return
        viewModelScope.launch {
            try {
                _capabilities.value = repo.fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Leave it unknown. The gated tabs stay hidden, the rest of
                // the app is unaffected, and the next bind tries again.
                _capabilities.value = null
            }
        }
    }

    override fun onCleared() {
        repository?.close()
        repository = null
    }
}
