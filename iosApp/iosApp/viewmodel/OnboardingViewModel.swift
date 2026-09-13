import Foundation
import SharedLogic

/// Drives signup and the onboarding steps after it.
///
/// The repository is built fresh in `bind()` and closed in `unbind()` — never a
/// singleton. Every stored `Task` is cancelled on unbind (kmp-arch-v2); the
/// ticket-6 demo did not, and must not be copied.
///
/// Where to go next is never decided here: `destination` reads the one shared
/// rule that Android reads too.
@MainActor
final class OnboardingViewModel: ObservableObject {

    @Published private(set) var configurationProblemKey: String?
    @Published private(set) var session: SessionState = .loading
    @Published private(set) var me: Me?
    @Published private(set) var meFailure: ApiException?
    @Published private(set) var startIsSlow = false

    @Published var dialCode: DialCode = DialCodes.shared.fallback
    @Published var phoneDigits = ""
    @Published private(set) var codeSent = false
    @Published var code = "" { didSet { code = String(code.filter(\.isNumber).prefix(Self.codeLength)) } }
    @Published private(set) var resendSeconds = 0

    @Published private(set) var terms: Terms?
    @Published private(set) var busy = false
    @Published private(set) var errorKey: String?

    /// A provider sign-in that failed, kept apart from `errorKey`.
    ///
    /// They are shown in different places and mean different things: one is
    /// about what the user typed, the other about a button they pressed.
    /// Sharing a field turned the phone box red because Google was
    /// misconfigured.
    @Published private(set) var providerErrorKey: String?

    static let codeLength = 6
    private static let minPhoneDigits = 4
    private static let resendSecondsStart = 60

    private var auth: AuthRepository?
    private var sessionTask: Task<Void, Never>?
    private var resendTask: Task<Void, Never>?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    // MARK: - Derived

    var destination: Destination {
        OnboardingRouter.shared.destinationFor(session: session, me: me, failure: meFailure)
    }

    private var digits: String { phoneDigits.filter(\.isNumber) }

    /// The number as the API wants it: digits only behind a `+`.
    var e164: String { "+" + dialCode.code + digits }

    var canSendCode: Bool { !busy && digits.count >= Self.minPhoneDigits }
    var canVerify: Bool { !busy && code.count == Self.codeLength }
    var canResend: Bool { !busy && resendSeconds == 0 }
    var showCodeScreen: Bool { codeSent }

    var resendCountdown: String {
        String(format: "%d:%02d", resendSeconds / 60, resendSeconds % 60)
    }

    // MARK: - Lifecycle

    func bind() {
        guard auth == nil, configurationProblemKey == nil else { return }

        dialCode = DialCodes.shared.defaultFor(
            // For pre-selecting a dialling code only. The REGION comes from the
            // server reading the verified number (PRD §4.6).
            deviceRegion: Locale.current.region?.identifier,
            timeZoneId: TimeZone.current.identifier
        )

        if let problem = Supabase.shared.configurationProblem {
            configurationProblemKey = problem.messageKey
            return
        }
        guard let client = Supabase.shared.clientOrNull() else { return }
        let repository = SupabaseAuthRepository(
            client: client,
            baseUrl: ApiConfig.shared.BASE_URL,
            tokens: SupabaseTokenSource(client: client),
            logging: logging
        )
        auth = repository

        sessionTask = Task { [weak self] in
            for await state in repository.sessionState {
                guard let self else { return }
                let was = self.session
                self.session = state
                if state == .signedIn {
                    self.loadMe()
                } else if state == .signedOut, was != .signedOut {
                    self.me = nil
                    self.meFailure = nil
                    self.terms = nil
                    self.code = ""
                    self.codeSent = false
                }
            }
        }

    }

    /// A motionless logo is indistinguishable from a hang, and Render's free tier
    /// can take most of a minute to wake. After a few seconds the splash says so.
    ///
    /// Driven by the splash view's own lifetime, for as long as it is on screen,
    /// rather than once at startup. The splash a user actually waits on is
    /// usually the SECOND one - after verifying a code, while the first `/me`
    /// loads - and a one-shot timer armed at launch has always expired by then.
    func watchForSlowStart() async {
        startIsSlow = false
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        guard !Task.isCancelled else { return }
        startIsSlow = true
    }

    func unbind() {
        sessionTask?.cancel()
        resendTask?.cancel()
        sessionTask = nil
        resendTask = nil
        auth?.close()
        auth = nil
    }

    // MARK: - Actions

    func loadMe() {
        guard let auth else { return }
        Task { [weak self] in
            do {
                let loaded = try await auth.me()
                self?.me = loaded
                self?.meFailure = nil
                if loaded.onboardingRequired.first == .consent { self?.loadTerms() }
            } catch {
                self?.meFailure = Self.apiException(error)
            }
        }
    }

    /// Sends the code. A signed-in user is ATTACHING a number, a different call.
    func sendCode() {
        guard let auth, canSendCode else { return }
        let number = e164
        let linking = session == .signedIn
        perform {
            if linking {
                try await auth.requestPhoneLink(phone: number)
            } else {
                try await auth.requestPhoneCode(phone: number)
            }
        } onSuccess: { [weak self] in
            self?.codeSent = true
            self?.code = ""
            self?.startResendCountdown()
        }
    }

    func verifyCode() {
        guard let auth, canVerify else { return }
        let number = e164
        let entered = code
        let linking = session == .signedIn
        perform {
            if linking {
                try await auth.verifyPhoneLink(phone: number, code: entered)
            } else {
                try await auth.verifyPhoneCode(phone: number, code: entered)
            }
        } onSuccess: { [weak self] in
            self?.codeSent = false
            self?.code = ""
            // Linking keeps the same session, so no new signedIn arrives to
            // trigger a reload — ask for the new state directly.
            if linking { self?.loadMe() }
        }
    }

    /// Back from the code screen, to fix a mistyped number.
    func editNumber() {
        resendTask?.cancel()
        codeSent = false
        code = ""
        resendSeconds = 0
        errorKey = nil
    }

    func loadTerms() {
        guard let auth else { return }
        Task { [weak self] in
            do {
                self?.terms = try await auth.terms()
            } catch {
                self?.errorKey = Self.messageKey(error)
            }
        }
    }

    func acceptTerms() {
        guard let auth, let version = terms?.version else { return }
        perform {
            let updated = try await auth.acceptTerms(version: version)
            await MainActor.run { self.me = updated }
        } onSuccess: {}
    }

    func setRegion(_ countryCode: String) {
        guard let auth else { return }
        perform {
            let updated = try await auth.setRegion(countryCode: countryCode)
            await MainActor.run {
                self.me = updated
                if updated.onboardingRequired.first == .consent { self.loadTerms() }
            }
        } onSuccess: {}
    }

    /// Signs in with an ID token the platform obtained natively.
    ///
    /// Only half a signup: every route ends at a verified phone, so the router
    /// sends the user to the phone step next (PRD §4.6).
    func signInWithProvider(_ provider: SocialProvider, idToken: String, nonce: String?) {
        guard let auth else { return }
        perform {
            try await auth.signInWithIdToken(provider: provider, idToken: idToken, nonce: nonce)
        } onSuccess: {}
    }

    /// The provider sheet was dismissed. Silently back — a cancel is not an error.
    func onProviderCancelled() {
        busy = false
        providerErrorKey = nil
    }

    func onProviderFailed(_ messageKey: String) {
        busy = false
        providerErrorKey = messageKey
    }

    func onProviderStarted() {
        busy = true
        errorKey = nil
        providerErrorKey = nil
    }

    func signOut() {
        guard let auth else { return }
        perform { try await auth.signOut() } onSuccess: {}
    }

    func retry() {
        meFailure = nil
        errorKey = nil
        loadMe()
    }

    // MARK: - Plumbing

    private func startResendCountdown() {
        resendTask?.cancel()
        resendTask = Task { [weak self] in
            var second = Self.resendSecondsStart
            while second >= 0, !Task.isCancelled {
                self?.resendSeconds = second
                if second > 0 { try? await Task.sleep(nanoseconds: 1_000_000_000) }
                second -= 1
            }
        }
    }

    private func perform(
        _ work: @escaping () async throws -> Void,
        onSuccess: @escaping () -> Void
    ) {
        busy = true
        errorKey = nil
        Task { [weak self] in
            do {
                try await work()
                onSuccess()
            } catch {
                // The terms moved on: reload so the user reads what they are
                // actually agreeing to.
                if let api = Self.apiException(error), api is ApiException.TermsChanged {
                    self?.loadTerms()
                }
                self?.errorKey = Self.messageKey(error)
            }
            self?.busy = false
        }
    }

    /// Recovers the typed Kotlin exception rather than string-matching messages
    /// (kmp-arch-v2 → SKIE).
    private static func apiException(_ error: Error) -> ApiException? {
        (error as NSError).userInfo["KotlinException"] as? ApiException
    }

    private static func messageKey(_ error: Error) -> String {
        apiException(error)?.messageKey ?? Strings.shared.error_unexpected
    }
}
