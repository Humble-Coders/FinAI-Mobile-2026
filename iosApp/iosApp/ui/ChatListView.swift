import SwiftUI
import SharedLogic

/**
 The Coach tab (#54): the list of conversations, or the one open in it. The
 tab bar steps aside while a conversation is open — it has its own bar.
 Android's `ChatRoute`.
 */
struct CoachView: View {

    @ObservedObject var model: ChatViewModel
    let userId: String

    /// The open conversation and its draft as the system keeps them for this
    /// scene, so the app coming back after iOS reclaimed it reopens them.
    @SceneStorage("chat.snapshot") private var stored = ""

    var body: some View {
        NavigationStack {
            Group {
                if model.openId == nil {
                    ChatListView(model: model)
                } else {
                    ChatView(model: model)
                        .toolbar(.hidden, for: .tabBar)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear {
            if !stored.isEmpty { model.restore(from: stored) }
            model.bind(userId: userId)
        }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        .sheet(isPresented: consentShown) {
            ScrollView {
                AiConsentPanel(
                    title: L.t(Strings.shared.chat_consent_title),
                    policy: model.policy,
                    ticked: $model.consentTicked,
                    busy: model.consentBusy,
                    errorKey: model.consentErrorKey,
                    onAgree: { model.agree() },
                    onNotNow: { model.declineConsent() }
                )
                .padding(20)
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $model.showingDisclaimer) {
            if let terms = model.disclaimer {
                ScrollView {
                    Text(terms.body)
                        .font(.body)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(20)
                }
                .presentationDetents([.medium, .large])
            }
        }
    }

    private var consentShown: Binding<Bool> {
        Binding(get: { model.consentAsked }, set: { if !$0 { model.declineConsent() } })
    }
}

/**
 The person's conversations with the coach, newest first (#54).

 In Home's language — the green field, a white sheet — because no design was
 supplied. Swipe a conversation away to delete it; VoiceOver offers the same
 as an action.
 */
struct ChatListView: View {

    @ObservedObject var model: ChatViewModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        ZStack(alignment: .top) {
            ZStack {
                Field.gradient(dark)
                Waves()
                FieldVectors()
            }
            .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 16) {
                header
                // The coin covers a first load; an empty sheet under it would
                // read as having no conversations.
                if !(model.listLoading && !model.listLoaded) {
                    sheet(dark)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 20)
            .padding(.top, 12)
        }
    }

    private var header: some View {
        HStack {
            Text(L.t(Strings.shared.chat_title))
                .font(.largeTitle.weight(.bold))
                .foregroundColor(Field.ink())
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button { model.newChat() } label: {
                Label(L.t(Strings.shared.chat_new), systemImage: "plus")
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Field.ink())
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(minHeight: 44)
                    .background(Capsule().fill(Field.glass))
                    .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
    }

    private func sheet(_ dark: Bool) -> some View {
        List {
            if let key = model.listErrorKey {
                VStack(alignment: .leading, spacing: 8) {
                    ErrorText(messageKey: key)
                    Button(L.t(Strings.shared.chat_retry)) { model.loadList() }
                        .font(.subheadline.weight(.semibold))
                }
                .listRowBackground(Mint.panel(dark))
            }
            if model.listEmpty {
                Text(L.t(Strings.shared.chat_list_empty))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .listRowBackground(Mint.panel(dark))
            }
            ForEach(model.conversations, id: \.id) { conversation in
                Button { model.open(conversation.id) } label: { row(conversation) }
                    .buttonStyle(.plain)
                    .listRowBackground(Mint.panel(dark))
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) { model.delete(conversation.id) } label: {
                            Label(L.t(Strings.shared.chat_delete), systemImage: "trash")
                        }
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Mint.panel(dark))
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .refreshable { model.loadList() }
    }

    private func row(_ conversation: ConversationSummary) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(conversation.title ?? L.t(Strings.shared.chat_untitled))
                .font(.subheadline.weight(.semibold))
                .foregroundColor(.primary)
                .lineLimit(1)
                .truncationMode(.tail)
            if let preview = conversation.preview {
                Text(preview)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .contentShape(Rectangle())
    }
}
