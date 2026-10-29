import SwiftUI
import SharedLogic

/**
 One conversation with the coach (#54). Android's `ChatScreen`.

 The coach's text is shown exactly as it arrived — every figure in it is the
 server's. What shows when is shared `ChatThread`'s.
 */
struct ChatView: View {

    @ObservedObject var model: ChatViewModel
    @Environment(\.colorScheme) private var scheme
    @FocusState private var typing: Bool

    private static let bottom = "bottom"

    var body: some View {
        let dark = scheme == .dark
        VStack(spacing: 0) {
            topBar(dark)
            if let thread = model.thread {
                conversation(thread, dark)
            } else if let key = model.threadErrorKey {
                VStack(alignment: .leading, spacing: 8) {
                    ErrorText(messageKey: key)
                    Button(L.t(Strings.shared.chat_retry)) { if let id = model.openId { model.open(id) } }
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                }
                .padding(20)
            } else {
                Spacer()
            }
        }
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .safeAreaInset(edge: .bottom) {
            if let thread = model.thread { inputBar(thread, dark) }
        }
        .background(Mint.ground(dark).ignoresSafeArea())
    }

    // MARK: - The bar

    private func topBar(_ dark: Bool) -> some View {
        HStack(spacing: 4) {
            Button { model.close() } label: {
                Image(systemName: "chevron.backward")
                    .font(.body.weight(.semibold))
                    .tappableArea()
            }
            .accessibilityLabel(L.t(Strings.shared.chat_back))
            Text(model.thread?.title ?? L.t(Strings.shared.chat_untitled))
                .font(.headline)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            if model.disclaimer != nil {
                Button(L.t(Strings.shared.chat_disclaimer_link)) { model.showingDisclaimer = true }
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Mint.greenText(dark))
                    .tappableArea()
            }
        }
        .foregroundColor(.primary)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    // MARK: - The messages

    private func conversation(_ thread: ChatThreadState, _ dark: Bool) -> some View {
        let bubbles = thread.bubbles
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if thread.chips == .emptyChat {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(L.t(Strings.shared.chat_suggestions_title))
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(.secondary)
                            ForEach(thread.suggestions, id: \.id) { chip in
                                chipButton(chip, dark)
                            }
                        }
                    }
                    ForEach(Array(bubbles.enumerated()), id: \.offset) { _, bubble in
                        BubbleView(bubble: bubble, dark: dark)
                    }
                    if thread.isSending { TypingIndicator(dark: dark) }
                    Color.clear.frame(height: 1).id(Self.bottom)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
            // Tapping outside the field puts the keyboard away.
            .onTapGesture { typing = false }
            // The newest message is always in view, the typing indicator included.
            .onChange(of: bubbles.count) { _, _ in scrollDown(proxy) }
            .onChange(of: thread.isSending) { _, _ in scrollDown(proxy) }
            .onAppear { proxy.scrollTo(Self.bottom, anchor: .bottom) }
        }
    }

    private func scrollDown(_ proxy: ScrollViewProxy) {
        withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
    }

    private func chipButton(_ chip: ChatSuggestion, _ dark: Bool) -> some View {
        Button { model.sendSuggestion(chip) } label: {
            Text(chip.text)
                .font(.subheadline)
                .foregroundColor(Mint.greenText(dark))
                .lineLimit(2)
                .truncationMode(.tail)
                .multilineTextAlignment(.leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .frame(minHeight: 44)
                .background(Capsule().fill(Mint.panel(dark)))
                .overlay(Capsule().stroke(Mint.edge(dark), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: - The input

    private func inputBar(_ thread: ChatThreadState, _ dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if thread.showsLearning, let learning = thread.learning {
                LearningCard(progress: learning)
            }
            if thread.chips == .aboveInput {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(thread.suggestions, id: \.id) { chip in chipButton(chip, dark) }
                    }
                }
            }
            if let failure = thread.failure, !failure.needsConsent {
                HStack {
                    Text(L.t(failure.messageKey))
                        .font(.footnote)
                        .foregroundColor(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L.t(Strings.shared.chat_retry)) { model.retry() }
                        .font(.footnote.weight(.semibold))
                        .tappableArea()
                }
                .onAppear { AccessibilityNotification.Announcement(L.t(failure.messageKey)).post() }
            }
            HStack(alignment: .bottom, spacing: 8) {
                TextField(L.t(Strings.shared.chat_input_hint), text: draft(thread), axis: .vertical)
                    .lineLimit(1...5)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.send)
                    .focused($typing)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 24).fill(Mint.panel(dark)))
                    .overlay(RoundedRectangle(cornerRadius: 24).stroke(Mint.edge(dark), lineWidth: 1))
                Button { model.send() } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 34))
                        .foregroundColor(thread.canSend ? Mint.greenText(dark) : .secondary)
                        .frame(width: 48, height: 48)
                }
                .disabled(!thread.canSend)
                .accessibilityLabel(L.t(Strings.shared.chat_send))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .background(Mint.groundTop(dark).opacity(0.96))
    }

    /**
     The field's text. A field that grows to five lines puts Return into the
     text rather than submitting, so a Return typed at the end sends instead —
     as the keyboard's Send label promises. Pasted text keeps its line breaks.
     */
    private func draft(_ thread: ChatThreadState) -> Binding<String> {
        Binding(
            get: { model.draft },
            set: { new in
                let old = model.draft
                if new == old + "\n" {
                    if thread.canSend { model.send() }
                } else {
                    model.draft = new
                }
            }
        )
    }
}

/// A message: the person's on the right in green, the coach's on the left.
private struct BubbleView: View {

    let bubble: ChatBubble
    let dark: Bool

    var body: some View {
        let mine = bubble.role == .you
        VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
            Text(bubble.text)
                .font(.body)
                .foregroundColor(mine ? .white : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(mine ? Brand.greenDeep : Mint.panel(dark)))
                .frame(maxWidth: 440, alignment: mine ? .trailing : .leading)
                .textSelection(.enabled)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L.t(bubble.accessibilityKey, bubble.text))
            if bubble.disclaimerNote {
                Text(L.t(Strings.shared.chat_disclaimer_note))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: 440, alignment: .leading)
                    .padding(.leading, 6)
            }
        }
        .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
    }
}

/// Three dots that breathe — still, when the system asks for less motion.
private struct TypingIndicator: View {

    let dark: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lit = false

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.secondary)
                    .frame(width: 8, height: 8)
                    .opacity(reduceMotion ? 0.6 : ((index % 2 == 0) == lit ? 1 : 0.3))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Mint.panel(dark)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L.t(Strings.shared.chat_typing))
        .onAppear {
            AccessibilityNotification.Announcement(L.t(Strings.shared.chat_typing)).post()
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) { lit = true }
        }
    }
}
