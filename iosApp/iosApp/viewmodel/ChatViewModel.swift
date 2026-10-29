import Foundation
import SharedLogic

/**
 The Coach tab (#54, PRD F7): the conversation list and the one open in it.
 Android's `ChatViewModel`.

 Every decision about a conversation — what shows while a reply is on its way,
 what happens to typed text when sending fails, when consent is what is
 missing — is shared `ChatThread`'s; this sends requests and keeps the result.
 Swift cannot `copy()` the Kotlin state, so it only ever replaces it with what
 `ChatThread` returns.

 The open conversation and the half-typed question survive the scene going
 away: the snapshot carries the owner, so the bind after a restore recognises
 the same person instead of resetting what it just brought back.
 */
@MainActor
final class ChatViewModel: ObservableObject {

    // MARK: The list
    @Published private(set) var conversations: [ConversationSummary] = []
    @Published private(set) var listLoading = false
    @Published private(set) var listLoaded = false
    @Published private(set) var listErrorKey: String?

    // MARK: The open conversation
    @Published private(set) var openId: String?
    @Published private(set) var thread: ChatThreadState?
    @Published private(set) var threadLoading = false
    @Published private(set) var threadErrorKey: String?

    // MARK: The disclaimer behind "Not financial advice"
    @Published private(set) var disclaimer: Terms?
    @Published var showingDisclaimer = false

    // MARK: Consent, asked for when a typed question needs it
    @Published private(set) var consentAsked = false
    @Published private(set) var policy: AiPolicy?
    /// Unticked until the person ticks it — express consent, nothing pre-ticked.
    @Published var consentTicked = false
    @Published private(set) var consentBusy = false
    @Published private(set) var consentErrorKey: String?

    private var chatRepository: ChatRepository?
    private var consentRepository: AiConsentRepository?
    private var owner: String?
    /// The draft brought back by `restore`, until its conversation is open again.
    private var restoredDraft = ""
    /// Rises on every bind and every conversation opened, so a late answer cannot land elsewhere.
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
        guard chatRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        chatRepository = KtorChatRepository(baseUrl: base, tokens: tokens, logging: logging)
        consentRepository = KtorAiConsentRepository(baseUrl: base, tokens: tokens, logging: logging)
        loadList()
        loadDisclaimer()
        if let openId {
            // Reopened after the tabs were torn down: the field keeps its text.
            if let thread { restoredDraft = thread.draft }
            open(openId)
        }
    }

    func unbind() {
        chatRepository?.close()
        consentRepository?.close()
        chatRepository = nil
        consentRepository = nil
    }

    deinit {
        chatRepository?.close()
        consentRepository?.close()
    }

    private func reset() {
        unbind()
        generation += 1
        conversations = []
        listLoading = false
        listLoaded = false
        listErrorKey = nil
        openId = nil
        thread = nil
        threadLoading = false
        threadErrorKey = nil
        disclaimer = nil
        showingDisclaimer = false
        restoredDraft = ""
        clearConsent()
    }

    // MARK: The list

    var listEmpty: Bool { listLoaded && conversations.isEmpty && listErrorKey == nil }

    func loadList() {
        guard let chatRepository else { return }
        let started = generation
        listLoading = !listLoaded
        listErrorKey = nil
        Task {
            do {
                let list = try await chatRepository.conversations()
                guard started == generation else { return }
                conversations = list
                listLoading = false
                listLoaded = true
            } catch {
                guard started == generation, !Self.cancelled(error) else { return }
                // A refresh that fails keeps what it had.
                listLoading = false
                listErrorKey = Self.messageKey(error)
            }
        }
    }

    func newChat() {
        // One conversation per tap, however fast the taps.
        guard let chatRepository, !threadLoading else { return }
        threadLoading = true
        threadErrorKey = nil
        Task {
            do {
                let started = try await chatRepository.start()
                conversations = [started] + conversations
                open(started.id)
            } catch {
                guard !Self.cancelled(error) else { return }
                threadLoading = false
                listErrorKey = Self.messageKey(error)
            }
        }
    }

    func delete(_ id: String) {
        guard let chatRepository else { return }
        // Gone at once; put back if the server refuses.
        let before = conversations
        conversations = before.filter { $0.id != id }
        if openId == id { close() }
        Task {
            do {
                try await chatRepository.delete(conversationId: id)
            } catch {
                guard !Self.cancelled(error) else { return }
                conversations = before
                listErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: A conversation

    func open(_ id: String) {
        guard let chatRepository else { return }
        generation += 1
        let started = generation
        openId = id
        threadLoading = true
        threadErrorKey = nil
        clearConsent()
        Task {
            do {
                let conversation = try await chatRepository.conversation(id: id)
                // Chips are the server's; a chat without them still works.
                let chips = (try? await chatRepository.suggestions()) ?? []
                guard started == generation else { return }
                let opened = ChatThread.shared.opened(conversation: conversation, suggestions: chips)
                thread = ChatThread.shared.typed(state: opened, draft: restoredDraft)
                restoredDraft = ""
                threadLoading = false
            } catch {
                guard started == generation, !Self.cancelled(error) else { return }
                threadLoading = false
                threadErrorKey = Self.messageKey(error)
            }
        }
    }

    /// Back to the list.
    func close() {
        generation += 1
        openId = nil
        thread = nil
        threadLoading = false
        threadErrorKey = nil
        restoredDraft = ""
        clearConsent()
        loadList()
    }

    /// The field's text. Read by the view's binding; written back through `ChatThread`.
    var draft: String {
        get { thread?.draft ?? "" }
        set { if let thread { self.thread = ChatThread.shared.typed(state: thread, draft: newValue) } }
    }

    func send() {
        guard let thread else { return }
        let next = ChatThread.shared.sendDraft(state: thread)
        guard let outgoing = next.sending else { return }
        self.thread = next
        deliver(outgoing)
    }

    func sendSuggestion(_ suggestion: ChatSuggestion) {
        guard let thread else { return }
        let next = ChatThread.shared.sendSuggestion(state: thread, suggestion: suggestion)
        guard let outgoing = next.sending else { return }
        self.thread = next
        deliver(outgoing)
    }

    /// Send again what failed, without retyping.
    func retry() {
        guard let thread else { return }
        let next = ChatThread.shared.retry(state: thread)
        guard let outgoing = next.sending else { return }
        self.thread = next
        deliver(outgoing)
    }

    func dismissFailure() {
        guard let thread else { return }
        self.thread = ChatThread.shared.dismissed(state: thread)
    }

    private func deliver(_ outgoing: ChatOutgoing) {
        guard let chatRepository, let id = openId else { return }
        let started = generation
        Task {
            do {
                let reply = try await chatRepository.send(
                    conversationId: id,
                    text: outgoing.typed ? outgoing.text : "",
                    suggestionId: outgoing.suggestionId
                )
                guard started == generation, let thread else { return }
                self.thread = ChatThread.shared.replied(state: thread, reply: reply, at: ISO8601DateFormatter().string(from: Date()))
            } catch {
                guard started == generation, !Self.cancelled(error), let thread else { return }
                let kotlin = Self.kotlin(error) ?? KotlinThrowable(message: error.localizedDescription)
                let failed = ChatThread.shared.failed(state: thread, error: kotlin)
                self.thread = failed
                if failed.failure?.needsConsent == true { askForConsent() }
            }
        }
    }

    // MARK: Consent

    /// Reads the policy to show; `errorKey` says why it is being asked again.
    private func askForConsent(errorKey: String? = nil) {
        guard let consentRepository else { return }
        consentAsked = true
        policy = nil
        consentTicked = false
        consentBusy = true
        consentErrorKey = errorKey
        Task {
            do {
                policy = try await consentRepository.policy()
                consentBusy = false
            } catch {
                guard !Self.cancelled(error) else { return }
                consentBusy = false
                consentErrorKey = Self.messageKey(error)
            }
        }
    }

    /// Records consent to the version shown, then sends the question it was asked for.
    func agree() {
        guard let consentRepository, let policy, consentTicked, !consentBusy else { return }
        consentBusy = true
        consentErrorKey = nil
        Task {
            do {
                try await consentRepository.consent(version: policy.version)
                clearConsent()
                retry()
            } catch {
                guard !Self.cancelled(error) else { return }
                if let changed = Self.kotlin(error) as? ApiException.AiPolicyChanged {
                    // The policy moved on while it was open: show the new one to agree to.
                    askForConsent(errorKey: changed.messageKey)
                    return
                }
                consentBusy = false
                consentErrorKey = Self.messageKey(error)
            }
        }
    }

    /// "Not now": the question stays in the field; chips still work.
    func declineConsent() { clearConsent() }

    private func clearConsent() {
        consentAsked = false
        policy = nil
        consentTicked = false
        consentBusy = false
        consentErrorKey = nil
    }

    // MARK: The disclaimer

    private func loadDisclaimer() {
        guard let chatRepository else { return }
        let started = generation
        Task {
            // Quiet on failure: the link stays out until the next bind.
            guard let terms = try? await chatRepository.disclaimer(), started == generation else { return }
            disclaimer = terms
        }
    }

    // MARK: Surviving the scene

    private struct Snapshot: Codable {
        let owner: String
        let openId: String
        let draft: String
    }

    /// The open conversation and its draft, for `@SceneStorage`; empty on the list.
    var snapshot: String {
        guard let owner, let openId else { return "" }
        let value = Snapshot(owner: owner, openId: openId, draft: thread?.draft ?? restoredDraft)
        guard let data = try? JSONEncoder().encode(value) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    /// Put a `snapshot` back. Sets the owner too, so the bind that follows sees
    /// the same person and reopens it; someone else's is dropped by that bind.
    func restore(from snapshot: String) {
        guard openId == nil,
              let data = snapshot.data(using: .utf8),
              let value = try? JSONDecoder().decode(Snapshot.self, from: data) else { return }
        owner = value.owner
        openId = value.openId
        restoredDraft = value.draft
    }

    // MARK: Errors

    private static func kotlin(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    private static func messageKey(_ error: Error) -> String {
        (kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
    }

    /// A superseded call is not a failure to show.
    private static func cancelled(_ error: Error) -> Bool {
        error is CancellationError || kotlin(error) is KotlinCancellationException
    }
}
