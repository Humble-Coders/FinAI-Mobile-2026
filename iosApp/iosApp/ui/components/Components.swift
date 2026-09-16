import Lottie
import SwiftUI
import SharedLogic

/// The palette from the approved splash design, mirrored from Android's
/// `FinAiPalette` so the two apps cannot drift.
enum Brand {
    static let green = Color(red: 0x22 / 255, green: 0xC5 / 255, blue: 0x5E / 255)
    /// The dark end of the logo's gradient, used for the primary button.
    static let greenDeep = Color(red: 0x15 / 255, green: 0x80 / 255, blue: 0x3D / 255)
    static let onGreen = Color(red: 0x05 / 255, green: 0x2E / 255, blue: 0x16 / 255)

    static let ground = Color("Ground")
    /// The auth sheet's card: white on light, a lifted grey on dark.
    static let sheet = Color("Surface")
    static let surface = Color("Surface")
    /// A field inside the sheet, a shade off the card behind it.
    static let surfaceField = Color("Surface")
    static let border = Color("BorderColor")
    static let textMuted = Color("TextMuted")
}

/// Every screen's frame: the brand ground edge to edge, content inside the safe
/// area, and a width cap so a form does not stretch across an iPad.
///
/// **This scroll is the only one a screen gets.** Nothing placed inside may
/// scroll vertically as well: SwiftUI gives the gesture to the inner one, which
/// leaves the page barely movable. Content that is too tall simply scrolls,
/// which is also what keeps a screen usable at the largest Dynamic Type sizes.
struct ScreenScaffold<Content: View>: View {
    var alignment: HorizontalAlignment = .leading
    var centred = false
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack {
            Brand.ground.ignoresSafeArea()
            ScrollView {
                VStack(alignment: alignment, spacing: 16) {
                    if centred { Spacer(minLength: 0) }
                    content()
                    if centred { Spacer(minLength: 0) }
                }
                .frame(maxWidth: 480, alignment: alignment == .center ? .center : .leading)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.vertical, 24)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }
}

/// `FinAI`, with the AI in the accent — the wordmark from the logo.
///
/// Spelled by the shared `SplashIntro`, never here: `frame` is the finished
/// name everywhere except the splash, which animates through the others.
struct Wordmark: View {
    var frame: WordmarkFrame = SplashIntro.shared.finalFrame
    var font: Font = .largeTitle.weight(.bold)

    var body: some View {
        (Text(frame.plain).foregroundColor(.primary) + Text(frame.accent).foregroundColor(Brand.green).bold())
            .font(font)
            .lineLimit(1)
    }
}

/// The one accented control on a screen. Green carries the primary action and
/// nothing else, so a screen never shows two.
struct PrimaryButton: View {
    let title: String
    var enabled = true
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: { if !busy { action() } }) {
            ZStack {
                if busy {
                    ProgressView().tint(Brand.onGreen)
                } else {
                    Text(title).font(.headline)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(enabled && !busy ? Brand.green : Brand.green.opacity(0.4))
        .foregroundColor(Brand.onGreen)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .disabled(!enabled || busy)
    }
}

/// The one accented control on a screen, in the logo's green gradient.
struct GradientButton: View {
    let title: String
    var enabled = true
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: { if !busy { action() } }) {
            ZStack {
                if busy {
                    ProgressView().tint(Brand.onGreen)
                } else {
                    Text(title).font(.headline)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            // Without this the label is only tappable where the text is: the
            // gradient behind it belongs to the Button, not to the label.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(
            LinearGradient(colors: [Brand.green, Brand.greenDeep], startPoint: .leading, endPoint: .trailing)
                .opacity(enabled && !busy ? 1 : 0.5)
        )
        .foregroundColor(Brand.onGreen)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .disabled(!enabled || busy)
    }
}

/// An email or password field in the sheet's filled style.
struct SheetField: View {
    let placeholder: String
    let systemImage: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    var content: UITextContentType?
    var isError = false

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage).foregroundColor(Brand.textMuted)
            TextField(placeholder, text: $text)
                .keyboardType(keyboard)
                .textContentType(content)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .background(Brand.surfaceField)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(isError ? .red : .clear, lineWidth: 1))
    }
}

/// A password in the same style, hidden until the user asks to see it.
struct SheetPasswordField: View {
    @Binding var text: String
    var content: UITextContentType = .password
    var isError = false
    let onSubmit: () -> Void

    @State private var visible = false

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "lock").foregroundColor(Brand.textMuted)
            Group {
                if visible {
                    TextField(L.t(Strings.shared.welcome_password_label), text: $text)
                } else {
                    SecureField(L.t(Strings.shared.welcome_password_label), text: $text)
                }
            }
            .textContentType(content)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.done)
            .onSubmit(onSubmit)
            Button {
                visible.toggle()
            } label: {
                Image(systemName: visible ? "eye.slash" : "eye")
                    .foregroundColor(Brand.textMuted)
                    .tappableArea()
            }
            .accessibilityLabel(L.t(visible ? Strings.shared.action_hide : Strings.shared.action_show))
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .background(Brand.surfaceField)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(isError ? .red : .clear, lineWidth: 1))
    }
}

/// A provider as a circle, the way the design shows them.
///
/// Apple's branding rules allow a logo-only Sign in with Apple button when every
/// provider is shown the same way, at the same size — which is why Google and
/// Apple are the same circle here.
struct ProviderCircleButton<Logo: View>: View {
    let label: String
    var background: Color = Brand.surface
    var border: Color = Brand.border
    var enabled = true
    @ViewBuilder var logo: () -> Logo
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            logo()
                .frame(width: 60, height: 60)
                .background(background)
                .clipShape(Circle())
                .overlay(Circle().stroke(border, lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// Google's sign-in button, from their own asset pack.
///
/// The image carries the circle, the border and the mark, and has a dark
/// variant in the asset catalogue, so nothing here draws chrome behind it.
/// Their branding rules require this artwork rather than a redrawn mark.
struct GoogleSignInMark: View {
    var body: some View {
        Image("GoogleButton")
            .resizable()
            .scaledToFit()
    }
}

/// A provider route. Outlined, never accented: the form's own button is primary.
struct ProviderButton: View {
    let title: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 52)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundColor(.primary)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Brand.border, lineWidth: 1))
        .disabled(!enabled)
    }
}

/// `──── or ────`, between the email form and the provider routes.
struct OrDivider: View {
    var title: String = L.t(Strings.shared.welcome_or)

    var body: some View {
        HStack(spacing: 12) {
            Rectangle().fill(Brand.border).frame(height: 1)
            Text(title)
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
            Rectangle().fill(Brand.border).frame(height: 1)
        }
    }
}

extension View {
    /// The whole row taps, not just the letters.
    ///
    /// A SwiftUI `Button` whose label is plain text is only tappable where the
    /// glyphs are, which reads as a dead control. `contentShape` makes the
    /// padded frame the target, and 44pt is Apple's minimum.
    func tappableRow(minHeight: CGFloat = 44) -> some View {
        frame(maxWidth: .infinity, minHeight: minHeight).contentShape(Rectangle())
    }

    /// The same, for a control that must not stretch across its row.
    func tappableArea(minWidth: CGFloat = 44, minHeight: CGFloat = 44) -> some View {
        frame(minWidth: minWidth, minHeight: minHeight).contentShape(Rectangle())
    }
}

/// An inline failure, under the control that caused it.
struct ErrorText: View {
    let messageKey: String?

    var body: some View {
        if let messageKey {
            Text(L.t(messageKey))
                .font(.footnote)
                .foregroundColor(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A text field in the brand's filled style, with the keyboard it needs.
struct FormField: View {
    let placeholder: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    var content: UITextContentType?
    var isError = false

    var body: some View {
        TextField(placeholder, text: $text)
            .keyboardType(keyboard)
            .textContentType(content)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .frame(minHeight: 52)
            .padding(.horizontal, 14)
            .background(Brand.surface)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(isError ? .red : .clear, lineWidth: 1))
    }
}

/// A password, hidden until the user asks to see it. Show/Hide is a word, so
/// VoiceOver reads it without a separate label.
struct PasswordField: View {
    let placeholder: String
    @Binding var text: String
    /// `.password` to sign in, `.newPassword` so iOS offers a strong one.
    var content: UITextContentType = .password
    var isError = false
    let onSubmit: () -> Void

    @State private var visible = false

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if visible {
                    TextField(placeholder, text: $text)
                } else {
                    SecureField(placeholder, text: $text)
                }
            }
            .textContentType(content)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.done)
            .onSubmit(onSubmit)

            Button(L.t(visible ? Strings.shared.action_hide : Strings.shared.action_show)) {
                visible.toggle()
            }
            .font(.footnote.weight(.semibold))
            .foregroundColor(Brand.green)
            .tappableArea(minWidth: 56)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .background(Brand.surface)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(isError ? .red : .clear, lineWidth: 1))
    }
}


/// The green wash behind every signed-out screen: a gradient with a few blurred
/// shapes over it, from the design.
struct HeroWash: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        LinearGradient(
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
}

/// The coin that sits on the card's top edge.
struct CoinBadge: View {
    static let size: CGFloat = 88

    var body: some View {
        LottieView(animation: .named("coin_3d"))
            .looping()
            // Half its own speed: a slow turn rather than a spin.
            .animationSpeed(0.5)
            .frame(width: Self.size, height: Self.size)
            .accessibilityHidden(true)
    }
}

/// The white card every signed-out screen sits in.
///
/// While `busy`, the coin leaves its place on the edge and settles in the middle
/// of the card: it is the loading indicator, so the screens it frames show no
/// spinner of their own. It returns to the edge when the work ends.
///
/// It is **as tall as its content**: `ViewThatFits` takes the plain stack when
/// that fits and a scrolling one only when it does not, so a short form gets a
/// short card. It runs to the bottom edge, carrying its colour past the safe
/// area, and the coin straddles its top edge.
struct AuthCard<Content: View>: View {
    var busy = false
    @ViewBuilder var content: () -> Content

    /// Measured, not guessed: the card is as tall as its content, so its centre
    /// is only known once it has been laid out.
    @State private var cardHeight: CGFloat = 0

    var body: some View {
        ZStack(alignment: .top) {
            ViewThatFits(in: .vertical) {
                stack
                ScrollView { stack }.scrollBounceBehavior(.basedOnSize)
            }
            .background(Brand.sheet.ignoresSafeArea(edges: .bottom))
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
            .ignoresSafeArea(edges: .bottom)
            .padding(.top, CoinBadge.size / 2)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { cardHeight = $0 }

            CoinBadge()
                .offset(y: busy ? max(0, cardHeight / 2 - CoinBadge.size / 2) : 0)
                .animation(.easeInOut(duration: 0.42), value: busy)
        }
    }

    private var stack: some View {
        VStack(spacing: 0) {
            Capsule().fill(Brand.border).frame(width: 44, height: 4)
                .padding(.top, 12)
                .accessibilityHidden(true)
            content()
        }
        .padding(.horizontal, 24)
        .padding(.top, CoinBadge.size / 2 - 8)
        .padding(.bottom, 40)
        .frame(maxWidth: .infinity)
    }
}

/// The wash plus the card: the frame the code and phone steps share with the
/// welcome sheet.
struct CardScreen<Content: View>: View {
    var busy = false
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack(alignment: .bottom) {
            HeroWash().ignoresSafeArea()
            AuthCard(busy: busy) { content() }
        }
    }
}
