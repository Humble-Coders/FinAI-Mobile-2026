import Foundation
import SharedLogic

/**
 The household's goals, and every change to them (#52, PRD F5). Android's
 `GoalsViewModel`.

 Each write answers with the server's figures and that answer goes straight
 into state. Every figure is the server's; every sentence comes from shared
 `GoalEdit`, so this screen and Android's cannot word a goal differently.

 What is being typed survives the scene going away — **including the bind that
 follows a restore**: the snapshot carries the owner, so that bind recognises
 the same person instead of resetting the draft it just brought back.
 */
@MainActor
final class GoalsViewModel: ObservableObject {

    @Published private(set) var page: GoalsPage?
    @Published private(set) var currency = ""
    @Published private(set) var locale = "en"
    @Published private(set) var today = ManualEntry.shared.today()
    @Published private(set) var disclaimer: Terms?

    @Published private(set) var loading = true
    @Published private(set) var refreshing = false
    @Published private(set) var loadFailed = false
    /// Why the list could not be read. Cleared by the next read that works.
    @Published private(set) var errorKey: String?
    /// Why an action on the list was refused. Its own field: those refusals are
    /// often followed by a fresh read, which would otherwise wipe the reason.
    @Published private(set) var noticeKey: String?

    // MARK: The editor — Swift cannot `copy()` a Kotlin draft, so its fields live here
    @Published private(set) var creating = false
    @Published private(set) var editingId: String?
    @Published var name = "" { didSet { editErrorKey = nil } }
    @Published var kind: GoalKind? { didSet { editErrorKey = nil } }
    @Published var horizon: GoalHorizon? { didSet { editErrorKey = nil } }
    @Published var target = "" { didSet { editErrorKey = nil } }
    @Published var saved = "" { didSet { editErrorKey = nil } }
    @Published var targetDate: String? { didSet { editErrorKey = nil } }
    @Published var monthlyContribution = "" { didSet { editErrorKey = nil } }
    @Published private(set) var saving = false
    @Published private(set) var editErrorKey: String?

    // MARK: Add money
    @Published private(set) var addingToId: String?
    @Published var addAmount = "" { didSet { addErrorKey = nil } }
    @Published private(set) var adding = false
    @Published private(set) var addErrorKey: String?

    // MARK: Delete and order
    @Published var confirmingDeleteId: String?
    @Published private(set) var deleting = false
    @Published var reordering = false
    @Published private(set) var reorderBusy = false

    private var goalsRepository: GoalsRepository?
    private var capabilitiesRepository: CapabilitiesRepository?
    private var owner: String?
    private var generation = 0

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
        guard goalsRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        goalsRepository = KtorGoalsRepository(baseUrl: base, tokens: tokens, logging: logging)
        capabilitiesRepository = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        Task { await load() }
    }

    func unbind() {
        goalsRepository?.close()
        capabilitiesRepository?.close()
        goalsRepository = nil
        capabilitiesRepository = nil
    }

    private func reset() {
        unbind()
        generation += 1
        page = nil
        disclaimer = nil
        loading = true
        loadFailed = false
        errorKey = nil
        noticeKey = nil
        clearEditor()
        cancelAdd()
        confirmingDeleteId = nil
        reordering = false
    }

    func retry() { Task { await load() } }

    func load(refresh: Bool = false) async {
        guard let goals = goalsRepository else { return }
        let started = generation
        if refresh { refreshing = true } else { loading = true; loadFailed = false; errorKey = nil }
        // The currency and locale every figure is written in.
        let capabilities = try? await capabilitiesRepository?.fetch()
        do {
            let answer = try await goals.list()
            guard started == generation else { return }
            page = answer
            if let found = capabilities?.currency, !found.isEmpty { currency = found }
            if let found = capabilities?.locale, !found.isEmpty { locale = found }
            today = ManualEntry.shared.today()
            loading = false
            refreshing = false
            loadFailed = false
            errorKey = nil
            await loadDisclaimer(answer)
        } catch {
            guard started == generation else { return }
            loading = false
            refreshing = false
            // A refresh that fails keeps what it had (CLAUDE.md → Data & caching).
            loadFailed = page == nil
            errorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
        }
    }

    /// The disclaimer's text, once there is a long-term goal to show it under,
    /// read again only when its version changes. Failing quietly leaves the line
    /// out until the next read: the projection is still right.
    private func loadDisclaimer(_ answer: GoalsPage) async {
        guard let wanted = answer.disclaimerVersion, disclaimer?.version != wanted, let goals = goalsRepository else { return }
        if let terms = try? await goals.disclaimer() { disclaimer = terms }
    }

    /// A write the server confirmed: tell Home, and read the list again for the
    /// comparison and the order, which are the server's to work out.
    private func confirmed() async {
        GoalsChanged.announce()
        await load(refresh: true)
    }

    // MARK: What the screen shows

    var goals: [Goal] { page?.goals ?? [] }

    var words: GoalEdit.Words { GoalEdit.Words(currency: currency, locale: locale, amountsHidden: false) }

    var showsEmpty: Bool { !loading && !loadFailed && page != nil && goals.isEmpty }

    var comparisonLine: String? { page.flatMap { GoalEdit.shared.comparisonLine(page: $0, words: words) } }

    var hasShortfall: Bool { page?.budget?.shortfall != nil }

    var atLimit: Bool { Int(page?.openCount ?? 0) >= Int(GoalEdit.shared.OPEN_LIMIT) }

    func amounts(of goal: Goal) -> String { GoalEdit.shared.amountsLine(goal: goal, words: words) }

    func status(of goal: Goal) -> String { GoalEdit.shared.statusLine(goal: goal, words: words) }

    func description(of goal: Goal) -> String { GoalEdit.shared.description(goal: goal, words: words) }

    func growthLine(of goal: Goal) -> String? {
        guard let page, GoalEdit.shared.saysNoGrowth(goal: goal, page: page) else { return nil }
        return GoalEdit.shared.growthLine(words: words)
    }

    func disclaimer(of goal: Goal) -> String? {
        guard goal.isLongTerm, !goal.isAchieved, let body = disclaimer?.body, !body.isEmpty else { return nil }
        return body
    }

    func canMoveUp(_ index: Int) -> Bool { !reorderBusy && index > 0 }

    func canMoveDown(_ index: Int) -> Bool { !reorderBusy && index < goals.count - 1 }

    // MARK: The editor

    var editing: Goal? { editingId.flatMap { id in goals.first { $0.id == id } } }

    var editorOpen: Bool { creating || editing != nil }

    var draft: GoalDraft {
        GoalDraft(
            name: name,
            kind: kind,
            horizon: horizon,
            target: target,
            saved: saved,
            targetDate: targetDate,
            monthlyContribution: monthlyContribution
        )
    }

    /// Why Save is off, or nil. The one shared rule, read by the button, the
    /// notice and `save()` alike (CLAUDE.md → Blocking reasons).
    var editBlock: GoalBlock? {
        if creating {
            return GoalEdit.shared.blockingReasonForNew(draft: draft, currency: currency, today: today, openGoals: page?.openCount ?? 0)
        }
        guard let editing else { return nil }
        return GoalEdit.shared.blockingReason(original: editing, draft: draft, currency: currency, today: today)
    }

    /// "Nothing has changed" is not worth saying before anything is touched.
    var editNotice: GoalBlock? {
        guard let block = editBlock, block != .nothingChanged else { return nil }
        return block
    }

    var canSave: Bool { !saving && editBlock == nil }

    func startNew() {
        clearEditor()
        today = ManualEntry.shared.today()
        creating = true
    }

    func edit(goalId: String) {
        guard let goal = goals.first(where: { $0.id == goalId }) else { return }
        let start = GoalEdit.shared.draftOf(goal: goal)
        creating = false
        editingId = goal.id
        name = start.name
        kind = start.kind
        horizon = start.horizon
        target = start.target
        saved = start.saved
        targetDate = start.targetDate
        monthlyContribution = start.monthlyContribution
        today = ManualEntry.shared.today()
        editErrorKey = nil
    }

    func cancelEdit() { clearEditor() }

    private func clearEditor() {
        creating = false
        editingId = nil
        name = ""
        kind = nil
        horizon = nil
        target = ""
        saved = ""
        targetDate = nil
        monthlyContribution = ""
        saving = false
        editErrorKey = nil
    }

    /// Send the draft. The same shared rule the button reads decides this too;
    /// a failure keeps the sheet open with what was typed.
    func save() {
        guard let goals = goalsRepository, canSave else { return }
        let draft = self.draft
        let original = editing
        let isNew = creating
        saving = true
        editErrorKey = nil
        Task {
            do {
                if isNew {
                    guard let new = GoalEdit.shared.goalToCreate(draft: draft, currency: currency) else { saving = false; return }
                    _ = try await goals.create(goal: new)
                } else if let original {
                    _ = try await goals.update(id: original.id, changes: GoalEdit.shared.changes(original: original, draft: draft, currency: currency))
                }
                clearEditor()
                await confirmed()
            } catch {
                saving = false
                editErrorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    // MARK: Add money

    var addingTo: Goal? { addingToId.flatMap { id in goals.first { $0.id == id } } }

    var addBlock: AddMoneyBlock? {
        guard addingTo != nil else { return nil }
        return GoalEdit.shared.blockingReasonForAdd(amount: addAmount, currency: currency)
    }

    var addNotice: AddMoneyBlock? {
        guard let block = addBlock, block != .noAmount else { return nil }
        return block
    }

    var canAdd: Bool { !adding && addBlock == nil }

    func startAdd(goalId: String) {
        addingToId = goalId
        addAmount = ""
        addErrorKey = nil
    }

    func cancelAdd() {
        addingToId = nil
        addAmount = ""
        adding = false
        addErrorKey = nil
    }

    func add() {
        guard let goals = goalsRepository, let goal = addingTo, canAdd else { return }
        let amount = GoalEdit.shared.addAmount(amount: addAmount, currency: currency)
        adding = true
        Task {
            do {
                _ = try await goals.add(id: goal.id, amount: amount)
                cancelAdd()
                await confirmed()
            } catch {
                adding = false
                addErrorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    // MARK: Delete and order

    var confirmingDelete: Goal? { confirmingDeleteId.flatMap { id in goals.first { $0.id == id } } }

    func askDelete() {
        noticeKey = nil
        confirmingDeleteId = editingId
    }

    func delete() {
        guard let goals = goalsRepository, let goal = confirmingDelete else { return }
        deleting = true
        Task {
            do {
                _ = try await goals.delete(id: goal.id)
                deleting = false
                confirmingDeleteId = nil
                clearEditor()
                await confirmed()
            } catch {
                deleting = false
                confirmingDeleteId = nil
                noticeKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    func moveUp(_ index: Int) { move(from: index, to: index - 1) }

    func moveDown(_ index: Int) { move(from: index, to: index + 1) }

    /// Send the new order and show the server's answer — not before it agrees,
    /// so a failed reorder never puts the list back under the person's thumb.
    /// `order_mismatch` means the goals changed elsewhere: read them again, and
    /// keep saying why.
    private func move(from: Int, to: Int) {
        guard let goals = goalsRepository, !reorderBusy else { return }
        let ids = self.goals.map(\.id)
        let wanted = GoalEdit.shared.moved(ids: ids, from: Int32(from), to: Int32(to))
        guard wanted != ids else { return }
        reorderBusy = true
        noticeKey = nil
        Task {
            do {
                page = try await goals.reorder(ids: wanted)
                reorderBusy = false
                GoalsChanged.announce()
            } catch {
                reorderBusy = false
                noticeKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
                if error is ApiException.OrderMismatch { await load(refresh: true) }
            }
        }
    }

    // MARK: Surviving the scene going away

    private struct Snapshot: Codable {
        var owner: String
        var creating: Bool
        var editingId: String?
        var name: String
        var kind: String?
        var horizon: String?
        var target: String
        var saved: String
        var targetDate: String?
        var monthlyContribution: String
        var addingToId: String?
        var addAmount: String
    }

    /**
     The editor and the add-money sheet as they are, as one string the scene
     can keep. JSON rather than a delimited string: a goal's name is free text,
     and nothing typed should need sanitising to be remembered. Only what was
     typed travels — never the goals, which are re-read on bind.
     */
    var snapshot: String {
        guard let owner, creating || editingId != nil || addingToId != nil else { return "" }
        let value = Snapshot(
            owner: owner, creating: creating, editingId: editingId,
            name: name, kind: kind?.wire, horizon: horizon?.wire,
            target: target, saved: saved, targetDate: targetDate,
            monthlyContribution: monthlyContribution,
            addingToId: addingToId, addAmount: addAmount
        )
        guard let data = try? JSONEncoder().encode(value) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    /// Put a `snapshot` back. Sets the owner too, so the bind that follows sees
    /// the same person and keeps it; someone else's is dropped by that bind.
    func restore(from snapshot: String) {
        guard let data = snapshot.data(using: .utf8),
              let value = try? JSONDecoder().decode(Snapshot.self, from: data) else { return }
        owner = value.owner
        creating = value.creating
        editingId = value.editingId
        name = value.name
        kind = value.kind.map { GoalKind.companion.fromWire(value: $0) }.flatMap { $0 == .unknown ? nil : $0 }
        horizon = value.horizon.map { GoalHorizon.companion.fromWire(value: $0) }.flatMap { $0 == .unknown ? nil : $0 }
        target = value.target
        saved = value.saved
        targetDate = value.targetDate
        monthlyContribution = value.monthlyContribution
        addingToId = value.addingToId
        addAmount = value.addAmount
    }
}
