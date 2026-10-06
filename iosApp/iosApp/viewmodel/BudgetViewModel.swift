import Foundation
import SharedLogic

/**
 A month's budget, and the edits to it (#47, PRD F4).

 **A write answers with the whole budget, so nothing is stitched.** `PUT` and
 `DELETE` both return the recomputed month, and this model puts that straight
 into state rather than patching the line it sent and re-reading afterwards.
 The totals and every other suggestion can move on one edit; a patched copy
 would briefly show a combination that never existed.

 Every figure shown is the server's. The ordering, the bar fractions and the
 refusals come from shared `BudgetEdit`, so Android cannot disagree with this
 screen about any of them.
 */
@MainActor
final class BudgetViewModel: ObservableObject {

    @Published private(set) var month: String = BudgetViewModel.currentMonth()
    @Published private(set) var budget: Budget?
    @Published private(set) var categories: [SharedLogic.Category] = []
    @Published private(set) var locale = "en"

    @Published private(set) var loading = true
    @Published private(set) var refreshing = false
    @Published private(set) var loadFailed = false
    @Published private(set) var errorKey: String?

    // MARK: Editing one line
    @Published private(set) var editing: BudgetLine?
    @Published private(set) var editingIsNew = false
    @Published var draftAmount = "" {
        // The server's refusal was of the amount that was sent. Once the
        // person types again it describes something no longer on screen.
        didSet { if draftAmount != oldValue { editErrorKey = nil } }
    }
    @Published private(set) var saving = false
    @Published private(set) var resetting = false
    @Published private(set) var editErrorKey: String?
    @Published var picking = false

    private var budgets: BudgetRepository?
    private var categoriesRepository: CategoriesRepository?
    private var capabilities: CapabilitiesRepository?
    private var owner: String?
    private var generation = 0
    private var listener: Task<Void, Never>?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    // MARK: Binding

    func bind(userId: String) {
        guard !userId.isEmpty else { return }
        if owner != userId {
            reset()
            owner = userId
        }
        guard budgets == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        budgets = KtorBudgetRepository(baseUrl: base, tokens: tokens, logging: logging)
        categoriesRepository = KtorCategoriesRepository(baseUrl: base, tokens: tokens, logging: logging)
        capabilities = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        listenForChanges()
        Task { await load() }
    }

    func unbind() {
        listener?.cancel()
        listener = nil
        budgets?.close()
        categoriesRepository?.close()
        capabilities?.close()
        budgets = nil
        categoriesRepository = nil
        capabilities = nil
    }

    private func reset() {
        unbind()
        generation += 1
        month = BudgetViewModel.currentMonth()
        budget = nil
        categories = []
        loading = true
        refreshing = false
        loadFailed = false
        errorKey = nil
        cancelEdit()
        picking = false
    }

    /**
     Re-read when an import or a correction moves the ledger: every line's
     `spent` is a figure about transactions, so it goes stale behind them.

     `BudgetChanged` is deliberately not observed here. This screen is the
     only thing that posts it, and it already has the budget that write
     returned.
     */
    private func listenForChanges() {
        guard listener == nil else { return }
        listener = Task { [weak self] in
            for await _ in LedgerChanged.events {
                guard !Task.isCancelled else { return }
                await self?.load(refresh: true)
            }
        }
    }

    // MARK: Loading

    func showMonth(_ next: String) {
        guard next != month else { return }
        generation += 1
        month = next
        refreshing = true
        errorKey = nil
        loadFailed = false
        Task { await load(refresh: true) }
    }

    func retry() {
        Task { await load() }
    }

    func load(refresh: Bool = false) async {
        guard let budgets else { return }
        let started = generation
        if refresh {
            refreshing = true
        } else {
            loading = true
            loadFailed = false
            errorKey = nil
        }

        // Capabilities decide whether the tab exists at all. A failure to read
        // them leaves it as it was rather than hiding a tab the person was
        // using because one call did not land.
        let payload = try? await capabilities?.fetch()
        // Only the picker needs these. Losing them costs "Add a category",
        // not the budget.
        let fetchedCategories = (try? await categoriesRepository?.list()) ?? []

        do {
            let answer = try await budgets.get(month: month)
            guard started == generation else { return }
            settle(answer, categories: fetchedCategories, payload: payload)
        } catch {
            guard started == generation else { return }
            failed(error, categories: fetchedCategories, payload: payload)
        }
    }

    private func settle(_ answer: Budget, categories fetched: [SharedLogic.Category], payload: Capabilities?) {
        budget = answer
        // An editor restored from the scene, or open while an import moved
        // the figures, holds the line as it was when it opened. Swap it for
        // its current self so "Use suggestion" and "nothing changed" read
        // today's numbers; what the person typed is left alone. A line added
        // by hand has nothing to refresh from yet. Android's `freshEditing`.
        if let held = editing, !editingIsNew,
           let fresh = answer.allLines.first(where: { $0.categoryId == held.categoryId }) {
            editing = fresh
        }
        if !fetched.isEmpty { categories = fetched }
        if let found = payload?.locale, !found.isEmpty { locale = found }
        loading = false
        refreshing = false
        loadFailed = false
        errorKey = nil
    }

    private func failed(_ error: Error, categories fetched: [SharedLogic.Category], payload: Capabilities?) {
        let api = error as? ApiException
        if !fetched.isEmpty { categories = fetched }
        if let found = payload?.locale, !found.isEmpty { locale = found }
        loading = false
        refreshing = false
        // A refresh that fails keeps the figures it had rather than zeroing
        // them (CLAUDE.md → Data & caching).
        loadFailed = budget == nil
        errorKey = api?.messageKey
    }

    // MARK: Editing one line

    func edit(categoryId: String) {
        guard let line = budget?.allLines.first(where: { $0.categoryId == categoryId }) else { return }
        editing = line
        editingIsNew = false
        draftAmount = BudgetEdit.shared.draftOf(line: line).amount
        editErrorKey = nil
    }

    func addCategory() { picking = true }

    func cancelPicking() { picking = false }

    /**
     A category chosen from the picker becomes an editor on a line that does
     not exist yet. Its suggestion is zero — there is no history behind it —
     so there is nothing to offer putting it back to.
     */
    func pickCategory(categoryId: String) {
        guard let category = categories.first(where: { $0.id == categoryId }),
              BudgetEdit.shared.isBudgetable(slug: category.slug) else { return }
        picking = false
        editing = BudgetLine(
            categoryId: category.id,
            slug: category.slug,
            name: category.name,
            suggested: "0",
            allocated: "0",
            isUserSet: false,
            spent: "0"
        )
        editingIsNew = true
        draftAmount = ""
        editErrorKey = nil
    }

    func cancelEdit() {
        editing = nil
        editingIsNew = false
        draftAmount = ""
        editErrorKey = nil
    }

    // MARK: What the editor may do

    var currency: String { budget?.currency ?? "" }

    /// Why Save is off, or nil. The one shared rule, read by the button, the
    /// notice and `save()` alike (CLAUDE.md → Blocking reasons).
    var editBlock: BudgetBlock? {
        guard let editing else { return nil }
        let draft = BudgetDraft(amount: draftAmount)
        return editingIsNew
            ? BudgetEdit.shared.blockingReasonForNew(draft: draft, currency: currency)
            : BudgetEdit.shared.blockingReason(original: editing, draft: draft, currency: currency)
    }

    /// "Nothing has changed" is not worth saying before the person has typed.
    var editNotice: BudgetBlock? {
        guard let block = editBlock, block != .nothingChanged else { return nil }
        return block
    }

    var canSave: Bool { !saving && !resetting && editBlock == nil }

    var busy: Bool { saving || resetting }

    /**
     Send the line.

     The same shared call the button reads decides this too, so a draft the
     screen would not let through cannot arrive here by another path. A
     failure keeps the sheet open with what was typed: the person's input is
     the thing hardest to get back.
     */
    func save() {
        guard let budgets, let line = editing, canSave else { return }
        let amount = draftAmount
        saving = true
        editErrorKey = nil
        Task {
            do {
                let answer = try await budgets.setLine(month: month, categoryId: line.categoryId, amount: amount)
                budget = answer
                saving = false
                cancelEdit()
                BudgetChanged.announce()
            } catch {
                saving = false
                editErrorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    /**
     Put the line back to the server's current suggestion.

     A hand-added line with nothing behind it is removed instead — that is the
     server's doing, and the budget it answers with simply no longer has the
     line in it.
     */
    func useSuggestion() {
        guard let budgets, let line = editing, !resetting, !editingIsNew else { return }
        resetting = true
        editErrorKey = nil
        Task {
            do {
                let answer = try await budgets.resetLine(month: month, categoryId: line.categoryId)
                budget = answer
                resetting = false
                cancelEdit()
                BudgetChanged.announce()
            } catch {
                resetting = false
                editErrorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    // MARK: What the screen shows

    var isReady: Bool { budget?.isReady == true }

    var learning: LearningProgress? { budget?.learning }

    /// The spending lines, over budget first.
    var lines: [BudgetLine] {
        guard let budget else { return [] }
        return BudgetEdit.shared.ordered(lines: budget.lines, currency: budget.currency, order: .bySpent)
    }

    var savings: BudgetLine? { budget?.savings }

    var debt: BudgetLine? { budget?.debt }

    var showsEmpty: Bool {
        !loading && !loadFailed && budget != nil && lines.isEmpty && savings == nil && debt == nil
    }

    var months: [String] { BudgetEdit.shared.months(current: month, count: 12) }

    /**
     The categories a line can still be added for: everything the household
     can see, less `income` and `transfers`, less the ones already budgeted.
     */
    var pickable: [SharedLogic.Category] {
        let taken = Set((budget?.allLines ?? []).map(\.categoryId))
        return categories
            .filter { BudgetEdit.shared.isBudgetable(slug: $0.slug) && !taken.contains($0.id) }
            .sorted { left, right in
                if left.isSystem != right.isSystem { return !left.isSystem }
                return left.name.lowercased() < right.name.lowercased()
            }
    }

    // MARK: Surviving the scene going away

    /**
     The month being looked at and an amount half-typed, as one string the
     scene can keep (#47 UI standards, as manual entry does).

     Only the draft travels, never the budget: those figures are the server's
     and are re-read on bind, and a stale copy restored from disk is exactly
     the kind of number this screen must not invent.

     Tab-separated because a decimal string and a category id cannot contain
     a tab, and a format the system stores needs no parser of its own.
     */
    var snapshot: String {
        guard let editing, let owner else { return "" }
        return [owner, month, editing.categoryId, editing.slug, editing.name,
                editing.suggested, editing.allocated, editing.isUserSet ? "1" : "0",
                editing.spent, editingIsNew ? "1" : "0", draftAmount]
            .joined(separator: "\t")
    }

    /**
     Put a `snapshot` back. Anything unreadable is ignored rather than guessed
     at: an empty editor is a better answer than a wrong figure.

     Sets `owner` too. Without it the `bind` that always follows looks like a
     different person signing in, and its reset throws the restored draft away
     — which is what the first version of this did. A snapshot belonging to
     someone else is dropped by that same reset, so it never leaks across.
     */
    func restore(from snapshot: String) {
        let parts = snapshot.components(separatedBy: "\t")
        guard parts.count == 11, !parts[0].isEmpty, !parts[2].isEmpty else { return }
        owner = parts[0]
        month = parts[1]
        editing = BudgetLine(
            categoryId: parts[2],
            slug: parts[3],
            name: parts[4],
            suggested: parts[5],
            allocated: parts[6],
            isUserSet: parts[7] == "1",
            spent: parts[8]
        )
        editingIsNew = parts[9] == "1"
        draftAmount = parts[10]
    }

    private static func currentMonth() -> String {
        DashboardMonths.shared.wire(
            month: DashboardMonths.shared.current(
                timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault()
            )
        )
    }
}
