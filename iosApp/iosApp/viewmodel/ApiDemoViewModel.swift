import Foundation
import SharedLogic

/// Throwaway demo (ticket #6): proves sign-in → bearer token → `/capabilities`
/// works end to end from Swift. Replaced by real screens in M2.
///
/// Repositories are built fresh in `bind()` and closed in `unbind()` — never
/// singletons (kmp-arch-v2).
@MainActor
final class ApiDemoViewModel: ObservableObject {
    /// Set when this build cannot reach its backend; the view shows it instead of crashing.
    @Published private(set) var configurationProblemKey: String?
    @Published private(set) var session: SessionState = .loading
    @Published var phone = ""
    @Published var code = ""
    @Published private(set) var codeSent = false
    @Published private(set) var busy = false
    @Published private(set) var capabilities: Capabilities?
    @Published private(set) var errorKey: String?
    /// Refresh evidence: token life in seconds right after expiring it, and after the load.
    @Published private(set) var tokenLifeAfterExpire: Int64?
    @Published private(set) var tokenLifeAfterLoad: Int64?

    private var auth: SupabaseAuthRepository?
    private var capabilitiesRepository: KtorCapabilitiesRepository?
    private var sessionTask: Task<Void, Never>?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    func bind() {
        guard auth == nil, configurationProblemKey == nil else { return }
        if let problem = Supabase.shared.configurationProblem {
            configurationProblemKey = problem.messageKey
            return
        }
        guard let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let baseUrl = ApiConfig.shared.BASE_URL
        let auth = SupabaseAuthRepository(client: client, baseUrl: baseUrl, tokens: tokens, logging: logging)
        self.auth = auth
        capabilitiesRepository = KtorCapabilitiesRepository(baseUrl: baseUrl, tokens: tokens, logging: logging)
        sessionTask = Task { [weak self] in
            for await state in auth.sessionState {
                self?.session = state
            }
        }
    }

    func unbind() {
        sessionTask?.cancel()
        sessionTask = nil
        auth?.close()
        capabilitiesRepository?.close()
        auth = nil
        capabilitiesRepository = nil
    }

    func sendCode() {
        guard let auth else { return }
        let phone = self.phone
        perform { try await auth.requestPhoneCode(phone: phone) } onSuccess: { [weak self] in self?.codeSent = true }
    }

    func verifyCode() {
        guard let auth else { return }
        let phone = self.phone
        let code = self.code
        perform { try await auth.verifyPhoneCode(phone: phone, code: code) } onSuccess: {}
    }

    func loadCapabilities() {
        guard let repository = capabilitiesRepository else { return }
        busy = true
        errorKey = nil
        Task { [weak self] in
            do {
                let result = try await repository.fetch()
                self?.capabilities = result
            } catch {
                self?.errorKey = ApiDemoViewModel.messageKey(for: error)
            }
            self?.busy = false
        }
    }

    /// Marks the access token expired, then loads — the next request must refresh first.
    func expireTokenThenLoad() {
        guard let auth, let repository = capabilitiesRepository else { return }
        busy = true
        errorKey = nil
        Task { [weak self] in
            do {
                try await auth.expireAccessTokenForTesting()
                let afterExpire = auth.tokenSecondsLeftForTesting()?.int64Value
                let result = try await repository.fetch()
                let afterLoad = auth.tokenSecondsLeftForTesting()?.int64Value
                self?.capabilities = result
                self?.tokenLifeAfterExpire = afterExpire
                self?.tokenLifeAfterLoad = afterLoad
            } catch {
                self?.errorKey = ApiDemoViewModel.messageKey(for: error)
            }
            self?.busy = false
        }
    }

    func signOut() {
        guard let auth else { return }
        perform { try await auth.signOut() } onSuccess: { [weak self] in
            self?.capabilities = nil
            self?.codeSent = false
            self?.code = ""
        }
    }

    private func perform(_ work: @escaping () async throws -> Void, onSuccess: @escaping () -> Void) {
        busy = true
        errorKey = nil
        Task { [weak self] in
            do {
                try await work()
                onSuccess()
            } catch {
                self?.errorKey = ApiDemoViewModel.messageKey(for: error)
            }
            self?.busy = false
        }
    }

    /// Recovers the typed Kotlin exception rather than string-matching messages (kmp-arch-v2 → SKIE).
    static func messageKey(for error: Error) -> String {
        if let api = (error as NSError).userInfo["KotlinException"] as? ApiException {
            return api.messageKey
        }
        return Strings.shared.error_unexpected
    }
}
