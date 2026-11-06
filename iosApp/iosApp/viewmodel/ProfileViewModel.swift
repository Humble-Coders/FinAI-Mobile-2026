import Foundation
import SharedLogic

/**
 Profile (PM decision, 2026-11-06): the person's name, the texts they agreed
 to, the AI consent, and deleting the account. Android's `ProfileViewModel`.

 Who the person is comes from `/me`, which `OnboardingViewModel` owns and the
 view reads directly; this model holds what only Profile asks for. Its
 repositories are built in `bind` and closed in `unbind` (kmp-arch-v2). What a
 name may be and when Delete is offered are shared `Profile`'s rules.
 */
@MainActor
final class ProfileViewModel: ObservableObject {

    // MARK: The texts and the AI consent
    @Published private(set) var terms: Terms?
    /// The region's disclaimer; nil when it has none, which hides its row.
    @Published private(set) var disclaimer: Terms?
    @Published private(set) var aiStatus: AiConsentStatus?
    /// The household's currency, from capabilities — never the device's.
    @Published private(set) var currency = ""
    /// Which text is open to read, if any.
    @Published var reading: Terms?
    @Published private(set) var readingErrorKey: String?

    // MARK: Turning AI on and off
    @Published var turningAiOn = false
    @Published private(set) var policy: AiPolicy?
    /// Unticked until the person ticks it — express consent, nothing pre-ticked.
    @Published var aiTicked = false
    @Published var confirmingAiOff = false
    @Published private(set) var aiBusy = false
    @Published private(set) var aiErrorKey: String?

    // MARK: The name
    @Published var editingName = false
    @Published var nameDraft = "" {
        didSet { if nameDraft != oldValue { nameErrorKey = nil } }
    }
    @Published private(set) var nameSaving = false
    @Published private(set) var nameErrorKey: String?

    // MARK: Deleting the account
    @Published var deleting = false
    @Published var deleteTyped = ""
    @Published private(set) var deleteBusy = false
    @Published private(set) var deleteErrorKey: String?

    /// A short confirmation at the top — "Saved" — cleared after a moment.
    @Published private(set) var noticeKey: String?

    private var legal: LegalRepository?
    private var auth: AuthRepository?
    private var capabilities: CapabilitiesRepository?
    private var owner: String?
    /// Rises on every bind, so a late answer for one person never lands for the next.
    private var generation = 0
    private var noticeTask: Task<Void, Never>?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    // MARK: Derived

    /// The rule Save reads, and the words under the field.
    var nameBlock: String? { Profile.shared.nameBlock(name: nameDraft) }
    var canSaveName: Bool { !nameSaving && nameBlock == nil }
    var canDelete: Bool { !deleteBusy && Profile.shared.canDelete(typed: deleteTyped) }
    var canTurnAiOn: Bool { policy != nil && aiTicked && !aiBusy }

    // MARK: Binding

    func bind(userId: String) {
        guard !userId.isEmpty else { return }
        if owner != userId {
            reset()
            owner = userId
        }
        guard legal == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        legal = KtorLegalRepository(baseUrl: base, tokens: tokens, logging: logging)
        auth = SupabaseAuthRepository(client: client, baseUrl: base, tokens: tokens, logging: logging)
        capabilities = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        load()
    }

    func unbind() {
        legal?.close()
        legal = nil
        auth?.close()
        auth = nil
        capabilities?.close()
        capabilities = nil
        generation += 1
        // A sheet's request was dropped with the clients; nothing stays busy.
        aiBusy = false
        nameSaving = false
        deleteBusy = false
    }

    private func reset() {
        unbind()
        terms = nil
        disclaimer = nil
        aiStatus = nil
        currency = ""
        reading = nil
        readingErrorKey = nil
        closeAiSheet()
        confirmingAiOff = false
        editingName = false
        nameDraft = ""
        nameErrorKey = nil
        deleting = false
        deleteTyped = ""
        deleteErrorKey = nil
        noticeKey = nil
    }

    /// Everything the rows show. Each part quietly on its own: one failing
    /// leaves its row out rather than the page.
    func load() {
        guard let legal else { return }
        let started = generation
        Task { [weak self] in
            let status = try? await legal.aiStatus()
            guard let self, started == self.generation else { return }
            self.aiStatus = status
        }
        Task { [weak self] in
            let found = try? await legal.disclaimer()
            guard let self, started == self.generation else { return }
            self.disclaimer = found
        }
        reloadCurrency()
    }

    /// After a change of region: the currency follows it.
    func reloadCurrency() {
        guard let capabilities else { return }
        let started = generation
        Task { [weak self] in
            guard let found = try? await capabilities.fetch() else { return }
            guard let self, started == self.generation else { return }
            self.currency = found.currency
        }
    }

    // MARK: Reading

    /// The terms in force, read fresh so what opens is what is in force now.
    func readTerms() {
        guard let legal else { return }
        let started = generation
        readingErrorKey = nil
        Task { [weak self] in
            do {
                let found = try await legal.terms()
                guard let self, started == self.generation else { return }
                self.terms = found
                self.reading = found
            } catch {
                guard let self, started == self.generation, !Self.cancelled(error) else { return }
                self.readingErrorKey = Self.messageKey(error)
            }
        }
    }

    func readDisclaimer() {
        reading = disclaimer
    }

    // MARK: AI

    /// "Turn on": the policy in force, to tick and allow.
    func startAiOn() {
        guard let legal else { return }
        let started = generation
        turningAiOn = true
        policy = nil
        aiTicked = false
        aiBusy = true
        aiErrorKey = nil
        Task { [weak self] in
            do {
                let found = try await legal.aiPolicy()
                guard let self, started == self.generation else { return }
                self.policy = found
                self.aiBusy = false
            } catch {
                guard let self, started == self.generation, !Self.cancelled(error) else { return }
                self.aiBusy = false
                self.aiErrorKey = Self.messageKey(error)
            }
        }
    }

    /// Records consent to exactly the version shown.
    func turnAiOn() {
        guard let legal, let shown = policy, canTurnAiOn else { return }
        let started = generation
        aiBusy = true
        aiErrorKey = nil
        Task { [weak self] in
            do {
                try await legal.consentToAi(version: shown.version)
                let status = try? await legal.aiStatus()
                guard let self, started == self.generation else { return }
                self.aiStatus = status ?? AiConsentStatus(consented: true, version: shown.version)
                self.closeAiSheet()
                self.show(Strings.shared.profile_saved)
            } catch {
                guard let self, started == self.generation, !Self.cancelled(error) else { return }
                if Self.kotlin(error) is ApiException.AiPolicyChanged {
                    // The policy moved on while it was open: show the new one to agree to.
                    self.startAiOn()
                    self.aiErrorKey = Self.messageKey(error)
                } else {
                    self.aiBusy = false
                    self.aiErrorKey = Self.messageKey(error)
                }
            }
        }
    }

    func closeAiSheet() {
        turningAiOn = false
        policy = nil
        aiTicked = false
        aiBusy = false
        aiErrorKey = nil
    }

    /// Confirmed "Turn off": the consent is taken back on the server.
    func turnAiOff() {
        guard let legal else { return }
        let started = generation
        aiBusy = true
        aiErrorKey = nil
        Task { [weak self] in
            do {
                let status = try await legal.withdrawAi()
                guard let self, started == self.generation else { return }
                self.aiStatus = status
                self.aiBusy = false
                self.show(Strings.shared.profile_saved)
            } catch {
                guard let self, started == self.generation, !Self.cancelled(error) else { return }
                self.aiBusy = false
                self.aiErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: The name

    func startNameEdit(current: String?) {
        nameDraft = current ?? ""
        nameErrorKey = nil
        editingName = true
    }

    /// Saves the name; `onSaved` gets the `/me` the server answered with.
    func saveName(onSaved: @escaping (Me) -> Void) {
        guard let auth, canSaveName else { return }
        let started = generation
        let name = nameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        nameSaving = true
        nameErrorKey = nil
        Task { [weak self] in
            do {
                let updated = try await auth.updateName(name: name)
                guard let self, started == self.generation else { return }
                self.nameSaving = false
                self.editingName = false
                onSaved(updated)
                self.show(Strings.shared.profile_saved)
            } catch {
                guard let self, started == self.generation, !Self.cancelled(error) else { return }
                self.nameSaving = false
                self.nameErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: Deleting

    func startDelete() {
        deleteTyped = ""
        deleteErrorKey = nil
        deleting = true
    }

    func cancelDelete() {
        guard !deleteBusy else { return }
        deleting = false
        deleteTyped = ""
        deleteErrorKey = nil
    }

    /// Deletes the account; the shared repository then drops the session,
    /// which onboarding observes. `onDeleted` makes sure the app gets there.
    func deleteAccount(onDeleted: @escaping () -> Void) {
        guard let auth, canDelete else { return }
        let started = generation
        deleteBusy = true
        deleteErrorKey = nil
        Task { [weak self] in
            do {
                try await auth.deleteAccount()
                guard let self, started == self.generation else { return }
                self.deleteBusy = false
                self.deleting = false
                self.deleteTyped = ""
                onDeleted()
            } catch {
                guard let self, started == self.generation, !Self.cancelled(error) else { return }
                self.deleteBusy = false
                self.deleteErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: The notice

    /// Says something short at the top, then lets it go.
    func show(_ key: String) {
        noticeTask?.cancel()
        noticeKey = key
        noticeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            self?.noticeKey = nil
        }
    }

    // MARK: Plumbing

    private static func kotlin(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    private static func messageKey(_ error: Error) -> String {
        (kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
    }

    private static func cancelled(_ error: Error) -> Bool {
        error is CancellationError || kotlin(error) is KotlinCancellationException
    }
}
