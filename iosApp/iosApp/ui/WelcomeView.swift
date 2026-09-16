import Lottie
import SwiftUI
import SharedLogic

/// Signed out: the brand, then a sheet that slides up for signing in or
/// creating an account with email and password, Google, or Apple.
///
/// One sheet with a mode rather than two screens. The providers need no such
/// distinction, and a person who opened the wrong one is a tap from the other.
struct WelcomeView: View {
    @ObservedObject var model: OnboardingViewModel

    @State private var sheetOpen = false
    @Environment(\.colorScheme) private var scheme

    private static let coinSize: CGFloat = 88

    var body: some View {
        ZStack(alignment: .bottom) {
            hero.ignoresSafeArea()
            brand
            if !sheetOpen { entryButtons }
            if sheetOpen {
                Color.black.opacity(0.35)
                    .ignoresSafeArea()
                    .onTapGesture { close() }
                    .accessibilityLabel(L.t(Strings.shared.action_close))
                    .accessibilityAddTraits(.isButton)
                sheet.transition(.move(edge: .bottom))
            }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.9), value: sheetOpen)
    }

    // MARK: - Pieces

    /// The design's soft green wash: a gradient with a few blurred shapes over it.
    private var hero: some View {
        let dark = scheme == .dark
        return LinearGradient(
            colors: dark
                ? [Color(red: 0.05, green: 0.14, blue: 0.09), Brand.ground]
                : [Color(red: 0.92, green: 0.97, blue: 0.94), Color(red: 0.83, green: 0.94, blue: 0.87)],
            startPoint: .top,
            endPoint: .bottom
        )
        .overlay {
            GeometryReader { geometry in
                let side = min(geometry.size.width, geometry.size.height)
                Circle().fill(Brand.green.opacity(dark ? 0.14 : 0.22))
                    .frame(width: side * 0.9)
                    .position(x: geometry.size.width * 0.05, y: geometry.size.height * 0.16)
                Circle().fill(Brand.green.opacity(dark ? 0.07 : 0.12))
                    .frame(width: side * 1.1)
                    .position(x: geometry.size.width * 1.02, y: geometry.size.height * 0.34)
                Circle().fill(Brand.green.opacity(dark ? 0.07 : 0.12))
                    .frame(width: side * 0.8)
                    .position(x: geometry.size.width * 0.2, y: geometry.size.height * 0.92)
            }
            .blur(radius: 20)
        }
    }

    private var brand: some View {
        VStack(spacing: 0) {
            Image("LogoMark")
                .resizable()
                .scaledToFit()
                .frame(width: 104, height: 104)
                .accessibilityHidden(true)
            Wordmark(font: .system(size: 40, weight: .bold))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L.t(Strings.shared.app_name))
                .padding(.top, 20)
            Text(L.t(Strings.shared.welcome_hero_line))
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .padding(.top, 12)
            Text(L.t(Strings.shared.app_tagline))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
        }
        .padding(.horizontal, 32)
        .frame(maxHeight: .infinity, alignment: .top)
        .padding(.top, 48)
    }

    /// The two ways in, where the design's page dots were.
    private var entryButtons: some View {
        VStack(spacing: 12) {
            GradientButton(title: L.t(Strings.shared.welcome_sign_up)) { open(.createAccount) }
            Button { open(.signIn) } label: {
                Text(L.t(Strings.shared.welcome_log_in))
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.plain)
            .foregroundColor(.primary)
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Brand.border, lineWidth: 1))
        }
        .frame(maxWidth: 480)
        .padding(.horizontal, 24)
        .padding(.bottom, 32)
        .transition(.opacity)
    }

    private var sheet: some View {
        AuthSheet(model: model, coinSize: Self.coinSize, onModeChange: { model.welcomeMode = $0 })
    }

    // MARK: - Actions

    private func open(_ mode: WelcomeMode) {
        model.welcomeMode = mode
        sheetOpen = true
    }

    private func close() {
        sheetOpen = false
    }
}

/// The sheet itself: the coin animation sits on its top edge, which is why the
/// card is inset from the top rather than filling the container.
private struct AuthSheet: View {
    @ObservedObject var model: OnboardingViewModel
    let coinSize: CGFloat
    let onModeChange: (WelcomeMode) -> Void

    @FocusState private var focused: Field?
    @Environment(\.colorScheme) private var scheme

    private enum Field { case email, password }

    /// Apple's logo needs high contrast against its circle, in either theme.
    private var appleBackground: Color { scheme == .dark ? .white : .black }
    private var appleForeground: Color { scheme == .dark ? .black : .white }

    var body: some View {
        ZStack(alignment: .top) {
            card.padding(.top, coinSize / 2)
            LottieView(animation: .named("coin_animation"))
                .looping()
                .frame(width: coinSize, height: coinSize)
                .accessibilityHidden(true)
        }
    }

    private var card: some View {
        ScrollView {
            VStack(spacing: 0) {
                Capsule().fill(Brand.border).frame(width: 44, height: 4)
                    .padding(.top, 12)
                    .accessibilityHidden(true)

                Text(L.t(model.creatingAccount
                         ? Strings.shared.welcome_create_title
                         : Strings.shared.welcome_sign_in_title))
                    .font(.title2.weight(.bold))
                    .padding(.top, 20)
                Text(L.t(model.creatingAccount
                         ? Strings.shared.welcome_create_subtitle
                         : Strings.shared.welcome_sign_in_subtitle))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)

                SheetField(
                    placeholder: L.t(Strings.shared.welcome_email_hint),
                    systemImage: "envelope",
                    text: $model.email,
                    keyboard: .emailAddress,
                    content: .username,
                    isError: model.errorKey != nil
                )
                .focused($focused, equals: .email)
                .submitLabel(.next)
                .onSubmit { focused = .password }
                .onChange(of: model.email) { model.clearFormError() }
                .accessibilityLabel(L.t(Strings.shared.welcome_email_label))
                .padding(.top, 24)

                SheetPasswordField(
                    text: $model.password,
                    content: model.creatingAccount ? .newPassword : .password,
                    isError: model.errorKey != nil
                ) { model.submitCredentials() }
                .focused($focused, equals: .password)
                .onChange(of: model.password) { model.clearFormError() }
                .padding(.top, 12)

                if model.creatingAccount {
                    Text(L.t(Strings.shared.welcome_password_rule))
                        .font(.footnote)
                        .foregroundColor(Brand.textMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)
                } else {
                    // Prominent on purpose: with email as a way in, a forgotten
                    // password is the commonest reason someone cannot get back.
                    Button(L.t(Strings.shared.welcome_forgot_password)) { model.startReset() }
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Brand.green)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .padding(.top, 8)
                }

                ErrorText(messageKey: model.errorKey).padding(.top, 8)

                GradientButton(
                    title: L.t(model.creatingAccount
                               ? Strings.shared.welcome_create_action
                               : Strings.shared.welcome_sign_in_action),
                    enabled: model.canSubmitCredentials,
                    busy: model.busy
                ) { model.submitCredentials() }
                .padding(.top, 16)

                OrDivider(title: L.t(Strings.shared.welcome_or_continue)).padding(.top, 20)

                HStack(spacing: 16) {
                    ProviderCircleButton(
                        label: L.t(Strings.shared.welcome_google),
                        enabled: !model.busy,
                        logo: { GoogleMark() }
                    ) { GoogleSignInLauncher.start(model: model) }

                    // Sign in with Apple is mandatory on iOS wherever another
                    // provider is offered (App Store guideline 4.8). Logo-only
                    // is allowed because every provider here is a circle.
                    ProviderCircleButton(
                        label: L.t(Strings.shared.welcome_apple),
                        background: appleBackground,
                        border: .clear,
                        enabled: !model.busy,
                        logo: {
                            Image(systemName: "applelogo")
                                .font(.system(size: 26))
                                .foregroundColor(appleForeground)
                        }
                    ) { AppleSignIn.start(model: model) }
                }
                .padding(.top, 16)
                // Under the buttons they belong to, not under the form.
                ErrorText(messageKey: model.providerErrorKey).padding(.top, 8)

                Button(L.t(model.creatingAccount
                           ? Strings.shared.welcome_have_account
                           : Strings.shared.welcome_need_account)) {
                    onModeChange(model.creatingAccount ? .signIn : .createAccount)
                }
                .font(.subheadline)
                .foregroundColor(Brand.green)
                .padding(.top, 16)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
        .background(Brand.sheet)
        .clipShape(UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .frame(maxHeight: 640)
    }
}
