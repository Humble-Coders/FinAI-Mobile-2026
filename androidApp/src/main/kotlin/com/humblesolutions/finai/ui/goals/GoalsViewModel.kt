package com.humblesolutions.finai.ui.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorGoalsRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.GoalHorizon
import com.humblesolutions.finai.model.GoalKind
import com.humblesolutions.finai.model.GoalsPage
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.GoalsRepository
import com.humblesolutions.finai.usecase.GoalDraft
import com.humblesolutions.finai.usecase.GoalEdit
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.util.GoalsChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The household's goals, and every change to them (#52, PRD F5).
 *
 * Each write answers with the server's figures — a goal, or the whole list
 * after a reorder — and that answer goes straight into state; nothing is
 * patched locally and re-read.
 *
 * What is being typed survives the process being reclaimed, through
 * [SavedStateHandle], **including the [bind] that follows a restore** — the
 * owner is kept there too, so that bind recognises the same person instead of
 * resetting the draft it just brought back (#47 shipped that broken twice).
 */
class GoalsViewModel(
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restored())
    val uiState: StateFlow<GoalsUiState> = _uiState.asStateFlow()

    private var repositories: GoalsRepositories? = null

    /** Whose goals are on screen, kept in saved state; see the class comment. */
    private var boundTo: String?
        get() = saved[KEY_OWNER]
        set(value) {
            saved[KEY_OWNER] = value
        }

    /** Rises on every bind, so a late answer for someone else cannot land. */
    private var generation = 0

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            GoalsRepositories(
                goals = KtorGoalsRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> GoalsRepositories?) {
        if (userId.isBlank()) return
        // Only a different person clears what is on screen; a restored process
        // is the same person, and what they were typing is the point.
        if (userId != boundTo) {
            repositories?.close()
            repositories = null
            generation++
            boundTo = userId
            _uiState.value = GoalsUiState()
            store(_uiState.value)
        }
        if (repositories != null) return
        repositories = build() ?: return
        load()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    fun load(refresh: Boolean = false) {
        val repos = repositories ?: return
        val started = generation
        update { if (refresh) it.copy(refreshing = true) else it.copy(loading = true, loadFailed = false, errorKey = null) }
        viewModelScope.launch {
            // Capabilities give the currency and the locale every figure is
            // written in. Without them the goals still show, in what was had.
            val capabilities = try {
                repos.capabilities.fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                null
            }
            try {
                val page = repos.goals.list()
                if (started != generation) return@launch
                update {
                    it.copy(
                        page = page,
                        currency = capabilities?.currency?.takeIf(String::isNotBlank) ?: it.currency,
                        locale = capabilities?.locale?.takeIf(String::isNotBlank) ?: it.locale,
                        today = ManualEntry.today(),
                        loading = false,
                        refreshing = false,
                        loadFailed = false,
                        errorKey = null,
                    )
                }
                loadDisclaimer(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // A refresh that fails keeps what it had (CLAUDE.md → Data & caching).
                        loadFailed = it.page == null,
                        errorKey = e.messageKey,
                    )
                }
            }
        }
    }

    /**
     * The disclaimer's text, once there is a long-term goal to show it under.
     * Read again only when the version changes. Failing quietly leaves the
     * projection without the line until the next read — the projection is
     * still right, and an error card for a missing footnote would be worse.
     */
    private fun loadDisclaimer(page: GoalsPage) {
        val repos = repositories ?: return
        val wanted = page.disclaimerVersion ?: return
        if (_uiState.value.disclaimer?.version == wanted) return
        viewModelScope.launch {
            val terms = try {
                repos.goals.disclaimer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                return@launch
            }
            update { it.copy(disclaimer = terms) }
        }
    }

    /** Put a page the server answered with into state, and tell Home. */
    private fun settle(page: GoalsPage) {
        update { it.copy(page = page) }
        loadDisclaimer(page)
        GoalsChanged.announce()
    }

    /** A single goal the server answered with, swapped into the list in place. */
    private fun settle(goal: com.humblesolutions.finai.model.Goal) {
        val page = _uiState.value.page ?: return load(refresh = true)
        val exists = page.goals.any { it.id == goal.id }
        // A new goal's place, and the comparison, are the server's to work
        // out: re-read the list rather than guess where it goes.
        if (!exists) {
            GoalsChanged.announce()
            return load(refresh = true)
        }
        update { it.copy(page = page.copy(goals = page.goals.map { g -> if (g.id == goal.id) goal else g })) }
        GoalsChanged.announce()
        // The comparison moves with every amount; read it fresh behind the swap.
        load(refresh = true)
    }

    // ── The editor ──────────────────────────────────────────────────────

    /** Open the editor on a new goal — unless the limit is reached, which the screen says instead. */
    fun startNew() = update {
        it.copy(creating = true, editingId = null, draft = GoalDraft(), editErrorKey = null, today = ManualEntry.today())
    }

    fun edit(goalId: String) {
        val goal = _uiState.value.goals.firstOrNull { it.id == goalId } ?: return
        update { it.copy(creating = false, editingId = goal.id, draft = GoalEdit.draftOf(goal), editErrorKey = null, today = ManualEntry.today()) }
    }

    fun onDraftChange(draft: GoalDraft) = update { it.copy(draft = draft, editErrorKey = null) }

    fun cancelEdit() = update { it.copy(creating = false, editingId = null, draft = GoalDraft(), editErrorKey = null) }

    /**
     * Send the draft. The same [GoalEdit] call the button reads decides this
     * too, so a draft the screen would not let through cannot arrive here by
     * another path. A failure keeps the sheet open with what was typed.
     */
    fun save() {
        val repos = repositories ?: return
        val state = _uiState.value
        if (state.saving || state.editBlock != null) return
        update { it.copy(saving = true, editErrorKey = null) }
        viewModelScope.launch {
            try {
                val goal = if (state.creating) {
                    val new = GoalEdit.goalToCreate(state.draft, state.currency) ?: return@launch update { it.copy(saving = false) }
                    repos.goals.create(new)
                } else {
                    val original = state.editing ?: return@launch update { it.copy(saving = false) }
                    repos.goals.update(original.id, GoalEdit.changes(original, state.draft, state.currency))
                }
                update { it.copy(saving = false, creating = false, editingId = null, draft = GoalDraft()) }
                settle(goal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(saving = false, editErrorKey = e.messageKey) }
            }
        }
    }

    // ── Add money ───────────────────────────────────────────────────────

    fun startAdd(goalId: String) = update { it.copy(addingToId = goalId, addAmount = "", addErrorKey = null) }

    fun onAddAmountChange(amount: String) = update { it.copy(addAmount = amount, addErrorKey = null) }

    fun cancelAdd() = update { it.copy(addingToId = null, addAmount = "", addErrorKey = null) }

    fun add() {
        val repos = repositories ?: return
        val state = _uiState.value
        val goal = state.addingTo ?: return
        if (!state.canAdd) return
        update { it.copy(adding = true, addErrorKey = null) }
        viewModelScope.launch {
            try {
                val updated = repos.goals.add(goal.id, GoalEdit.addAmount(state.addAmount, state.currency))
                update { it.copy(adding = false, addingToId = null, addAmount = "") }
                settle(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(adding = false, addErrorKey = e.messageKey) }
            }
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────

    fun askDelete(goalId: String) = update { it.copy(confirmingDeleteId = goalId, noticeKey = null) }

    fun cancelDelete() = update { it.copy(confirmingDeleteId = null) }

    fun delete() {
        val repos = repositories ?: return
        val goal = _uiState.value.confirmingDelete ?: return
        update { it.copy(deleting = true) }
        viewModelScope.launch {
            try {
                repos.goals.delete(goal.id)
                update { it.copy(deleting = false, confirmingDeleteId = null, creating = false, editingId = null, draft = GoalDraft()) }
                GoalsChanged.announce()
                load(refresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(deleting = false, confirmingDeleteId = null, noticeKey = e.messageKey) }
            }
        }
    }

    // ── Order ───────────────────────────────────────────────────────────

    fun toggleReordering() = update { it.copy(reordering = !it.reordering) }

    fun moveUp(index: Int) = move(index, index - 1)

    fun moveDown(index: Int) = move(index, index + 1)

    /**
     * Send the new order and show the server's answer. Not shown before the
     * server agrees: an order that then failed would put the list back under
     * the person's thumb. `order_mismatch` means the goals changed elsewhere —
     * the list is read again and the message says so.
     */
    private fun move(from: Int, to: Int) {
        val repos = repositories ?: return
        val state = _uiState.value
        if (state.reorderBusy) return
        val ids = state.goals.map { it.id }
        val wanted = GoalEdit.moved(ids, from, to)
        if (wanted == ids) return
        update { it.copy(reorderBusy = true, noticeKey = null) }
        viewModelScope.launch {
            try {
                val page = repos.goals.reorder(wanted)
                update { it.copy(reorderBusy = false) }
                settle(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(reorderBusy = false, noticeKey = e.messageKey) }
                if (e is ApiException.OrderMismatch) load(refresh = true)
            }
        }
    }

    // ── Saved state ─────────────────────────────────────────────────────

    /**
     * The editor and the add-money sheet as they were, after Android reclaimed
     * the process. Only what was typed — never the goals themselves, which are
     * the server's and are read again on bind.
     */
    private fun restored(): GoalsUiState = GoalsUiState(
        creating = saved[KEY_CREATING] ?: false,
        editingId = saved[KEY_EDITING_ID],
        draft = GoalDraft(
            name = saved[KEY_NAME] ?: "",
            kind = saved.get<String>(KEY_KIND)?.let(GoalKind::fromWire)?.takeIf { it != GoalKind.UNKNOWN },
            horizon = saved.get<String>(KEY_HORIZON)?.let(GoalHorizon::fromWire)?.takeIf { it != GoalHorizon.UNKNOWN },
            target = saved[KEY_TARGET] ?: "",
            saved = saved[KEY_SAVED] ?: "",
            targetDate = saved[KEY_DATE],
            monthlyContribution = saved[KEY_CONTRIBUTION] ?: "",
        ),
        addingToId = saved[KEY_ADDING_TO],
        addAmount = saved[KEY_ADD_AMOUNT] ?: "",
    )

    private fun store(state: GoalsUiState) {
        saved[KEY_CREATING] = state.creating
        saved[KEY_EDITING_ID] = state.editingId
        saved[KEY_NAME] = state.draft.name
        saved[KEY_KIND] = state.draft.kind?.wire
        saved[KEY_HORIZON] = state.draft.horizon?.wire
        saved[KEY_TARGET] = state.draft.target
        saved[KEY_SAVED] = state.draft.saved
        saved[KEY_DATE] = state.draft.targetDate
        saved[KEY_CONTRIBUTION] = state.draft.monthlyContribution
        saved[KEY_ADDING_TO] = state.addingToId
        saved[KEY_ADD_AMOUNT] = state.addAmount
    }

    private fun update(block: (GoalsUiState) -> GoalsUiState) {
        _uiState.update(block)
        store(_uiState.value)
    }

    internal companion object {
        private const val KEY_OWNER = "goals.owner"
        private const val KEY_CREATING = "goals.creating"
        private const val KEY_EDITING_ID = "goals.editing.id"
        private const val KEY_NAME = "goals.draft.name"
        private const val KEY_KIND = "goals.draft.kind"
        private const val KEY_HORIZON = "goals.draft.horizon"
        private const val KEY_TARGET = "goals.draft.target"
        private const val KEY_SAVED = "goals.draft.saved"
        private const val KEY_DATE = "goals.draft.date"
        private const val KEY_CONTRIBUTION = "goals.draft.contribution"
        private const val KEY_ADDING_TO = "goals.add.goal"
        private const val KEY_ADD_AMOUNT = "goals.add.amount"
    }
}

/** What this screen talks to. Closed together when the model is cleared. */
internal class GoalsRepositories(
    val goals: GoalsRepository,
    val capabilities: CapabilitiesRepository,
) {
    fun close() {
        goals.close()
        capabilities.close()
    }
}
