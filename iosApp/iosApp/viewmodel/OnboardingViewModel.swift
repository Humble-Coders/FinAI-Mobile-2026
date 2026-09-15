import Foundation
import SharedLogic

/// Drives sign-in, signup and the onboarding steps after it.
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
    /// The launch intro has played. Once per process, so a sign-out never replays it.
    @Published private(set) var introFinished = false

    // Welcome.
    @Published var welcomeMode: WelcomeMode = .createAccount {
        didSet { errorKey = nil; providerErrorKey = nil }
    }
    @Published var email = ""
    /// Cleared as soon as it has been sent; never kept once it is no longer needed.
    @Published var password = ""
    /// A signup code was emailed to this address; the code screen replaces the form.
    @Published private(set) var emailCodeFor: String?
    @Published private(set) var reset: ResetStage?

    // The phone step.
    @Published var dialCode: DialCode = DialCodes.shared.fallback
    @Published var phoneDigits = ""
    @Published private(set) var codeSent = false
    /// Digits only, at most `codeLength` of them.
    ///
    /// The guard is load-bearing. `@Published` routes assignment through the
    /// property wrapper's setter, so writing to `code` inside its own `didSet`
    /// re-enters `didSet` - unlike a plain stored property, where Swift
    /// suppresses that. Without the comparison it recursed until the stack
    /// overflowed, and `bind()` sets `code = ""` the moment Supabase reports no
    /// stored session: every signed-out launch crashed on the splash screen.
    @Published var code = "" {
        didSet {
            let sanitized = String(code.filter(\.isNumber).prefix(Self.codeLength))
            if sanitized != code { code = sanitized }
        }
    }
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

    /// SMS and email codes alike. Supabase's email code length is a project
    /// setting: keep it at 6.
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

    var creatingAccount: Bool { welcomeMode == .createAccount }

    var canSubmitCredentials: Bool {
        !busy && Credentials.shared.problem(email: email, password: password, creating: creatingAccount) == nil
    }
    var canRequestReset: Bool { !busy && Credentials.shared.looksLikeEmail(raw: email) }
    var canSaveNewPassword: Bool {
        !busy && Credentials.shared.passwordProblem(password: password, creating: true) == nil
    }

    var canSendCode: Bool { !busy && digits.count >= Self.minPhoneDigits }
    var canVerify: Bool { !busy && code.count == Self.codeLength }
    var canResend: Bool { !busy && resendSeconds == 0 }

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
                    self.phoneDigits = ""
                    self.reset = nil
                    self.emailCodeFor = nil
                    self.password = ""
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

    /// The launch splash finished its intro; routing may take over.
    func finishIntro() {
        introFinished = true
    }

    func unbind() {
        sessionTask?.cancel()
        resendTask?.cancel()
        sessionTask = nil
        resendTask = nil
        auth?.close()
        auth = nil
    }

    // MARK: - Welcome: email and password

    /// Creates the account or signs in, depending on the mode.
    func submitCredentials() {
        guard let auth, canSubmitCredentials else { return }
        let address = Credentials.shared.normalizeEmail(raw: email)
        let secret = password

        if creatingAccount {
            perform {
                try await auth.signUpWithEmail(email: address, password: secret)
            } onSuccess: { [weak self] in
                self?.showEmailCode(for: address)
            }
            return
        }

        var needsCode = false
        perform {
            do {
                try await auth.signInWithEmail(email: address, password: secret)
            } catch {
                guard Self.apiException(error) is ApiException.EmailNotConfirmed else { throw error }
                // An account that never entered its code. Send a fresh one
                // rather than naming a problem they cannot act on. A rate limit
                // means one was sent moments ago.
                needsCode = true
                do {
                    try await auth.resendSignupCode(email: address)
                } catch {
                    guard Self.apiException(error) is ApiException.TooManyAttempts else { throw error }
                }
            }
        } onSuccess: { [weak self] in
            if needsCode {
                self?.showEmailCode(for: address)
            } else {
                self?.password = ""
            }
        }
    }

    /// Typing again clears a failure about what was typed.
    func clearFormError() {
        if errorKey != nil { errorKey = nil }
    }

    private func showEmailCode(for address: String) {
        emailCodeFor = address
        code = ""
        password = ""
        startResendCountdown()
    }

    /// Verifies the emailed signup code, which signs the user in.
    func verifyEmailCode() {
        guard let auth, let address = emailCodeFor, canVerify else { return }
        let entered = code
        perform {
            try await auth.verifySignupCode(email: address, code: entered)
        } onSuccess: { [weak self] in
            self?.emailCodeFor = nil
            self?.code = ""
        }
    }

    func resendEmailCode() {
        guard let auth, let address = emailCodeFor else { return }
        perform {
            try await auth.resendSignupCode(email: address)
        } onSuccess: { [weak self] in
            self?.startResendCountdown()
        }
    }

    /// Back from the email code screen, to fix a mistyped address.
    func editEmail() {
        resendTask?.cancel()
        emailCodeFor = nil
        code = ""
        resendSeconds = 0
        errorKey = nil
    }

    // MARK: - Forgot password

    func startReset() {
        reset = .request
        password = ""
        code = ""
        errorKey = nil
        providerErrorKey = nil
    }

    func requestResetCode() {
        guard let auth, canRequestReset else { return }
        let address = Credentials.shared.normalizeEmail(raw: email)
        perform {
            try await auth.requestPasswordReset(email: address)
        } onSuccess: { [weak self] in
            self?.email = address
            self?.reset = .code
            self?.code = ""
            self?.startResendCountdown()
        }
    }

    func verifyResetCode() {
        guard let auth, canVerify else { return }
        let address = email
        let entered = code
        perform {
            try await auth.verifyPasswordResetCode(email: address, code: entered)
        } onSuccess: { [weak self] in
            self?.reset = .newPassword
            self?.code = ""
            self?.password = ""
        }
    }

    func saveNewPassword() {
        guard let auth, canSaveNewPassword else { return }
        let secret = password
        perform {
            try await auth.setNewPassword(password: secret)
        } onSuccess: { [weak self] in
            self?.reset = nil
            self?.password = ""
        }
    }

    /// Leaves the reset. Once the code has verified the user is signed in with
    /// a recovery session and no new password, so leaving then signs out: the
    /// app is never reached by a reset that did not finish.
    func cancelReset() {
        let signedInForReset = reset == .newPassword
        resendTask?.cancel()
        reset = nil
        code = ""
        password = ""
        resendSeconds = 0
        errorKey = nil
        if signedInForReset { signOut() }
    }

    // MARK: - The phone step

    /// Attaches the number to the signed-in account, which texts the code.
    ///
    /// A number another account already has fails as `PhoneAlreadyLinked`,
    /// shown under the field like any other refusal: the user types a different
    /// number. Nothing offers to sign in to or link with that account (manager
    /// decision, 2026-09-15).
    func sendCode() {
        guard let auth, canSendCode else { return }
        let number = e164
        perform {
            try await auth.requestPhoneLink(phone: number)
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
        perform {
            try await auth.verifyPhoneLink(phone: number, code: entered)
        } onSuccess: { [weak self] in
            self?.codeSent = false
            self?.code = ""
            // Linking keeps the same session, so no new signedIn arrives to
            // trigger a reload — ask for the new state directly.
            self?.loadMe()
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

    // MARK: - Google and Apple

    /// Signs in with an ID token the platform obtained natively.
    func signInWithProvider(_ provider: SocialProvider, idToken: String, nonce: String?) {
        guard let auth else { return }
        // Deliberately not `perform`: that reports into `errorKey`, which the
        // form fields render. A provider Supabase refuses - one not enabled in
        // the dashboard, say - would then read as though what was typed were
        // wrong. It belongs under the buttons it came from.
        busy = true
        providerErrorKey = nil
        Task { [weak self] in
            do {
                try await auth.signInWithIdToken(provider: provider, idToken: idToken, nonce: nonce)
            } catch {
                self?.providerErrorKey = Self.messageKey(error)
            }
            self?.busy = false
        }
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

    // MARK: - Session and onboarding

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
