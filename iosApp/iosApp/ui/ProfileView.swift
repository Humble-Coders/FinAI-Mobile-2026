import SwiftUI
import SharedLogic

/**
 Profile (PM decision, 2026-11-06), opened from the monogram in Home's header
 and from every "Open Profile": who the person is, their setup, every consent
 and disclaimer, signing out and deleting the account. Android's
 `ProfileScreen`.

 On the mint field the import uses, a panel per section. `/me` is
 onboarding's, read straight from `account`; a saved name goes back into it,
 so Home's monogram changes with it. A change of region is RootView's
 existing sheet, which reloads `/me` itself.
 */
struct ProfileView: View {
    @ObservedObject var model: ProfileViewModel
    @ObservedObject var account: OnboardingViewModel
    let onBack: () -> Void
    let onChangeRegion: () -> Void
    let onEditSetup: () -> Void
    /// The account is gone: the app must be signed out, whatever the session says.
    let onDeleted: () -> Void

    @Environment(\.colorScheme) private var scheme
    /// Content has scrolled under the header, which then turns solid.
    @State private var scrolledUnder = false

    var body: some View {
        ZStack {
            MintBackdrop(decoration: "person.crop.circle")
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let me = account.me {
                        top(me)
                        if let key = model.noticeKey { notice(key) }
                        accountSection(me)
                        setupSection
                        privacySection(me)
                        endSection
                    }
                }
                .modifier(ScrolledUnder(scrolled: $scrolledUnder))
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            MintHeader(title: L.t(Strings.shared.profile_title), solid: scrolledUnder, onBack: onBack)
        }
        .onAppear {
            model.bind(userId: account.me?.user.id ?? "")
            // "Your setup is updated", set as the wizard closed, said once here.
            if let key = model.noticeKey { AccessibilityNotification.Announcement(L.t(key)).post() }
        }
        .onDisappear { model.unbind() }
        // The currency follows the region.
        .onChange(of: account.me?.household.countryCode) { _, _ in model.reloadCurrency() }
        .onChange(of: model.noticeKey) { _, key in
            if let key { AccessibilityNotification.Announcement(L.t(key)).post() }
        }
        .sheet(isPresented: $model.editingName) { NameSheet(model: model, account: account) }
        .sheet(isPresented: readingShown) { readingSheet }
        .sheet(isPresented: aiOnShown) { aiOnSheet }
        .sheet(isPresented: $model.deleting) { DeleteAccountSheet(model: model, onDeleted: onDeleted) }
        .alert(L.t(Strings.shared.profile_ai_off_title), isPresented: $model.confirmingAiOff) {
            Button(L.t(Strings.shared.profile_ai_turn_off), role: .destructive) { model.turnAiOff() }
            Button(L.t(Strings.shared.action_cancel), role: .cancel) {}
        } message: {
            Text(L.t(Strings.shared.profile_ai_off_body))
        }
    }

    private var readingShown: Binding<Bool> {
        Binding(get: { model.reading != nil }, set: { if !$0 { model.reading = nil } })
    }

    private var aiOnShown: Binding<Bool> {
        Binding(get: { model.turningAiOn }, set: { if !$0 { model.closeAiSheet() } })
    }

    // MARK: - The top

    private func top(_ me: Me) -> some View {
        let dark = scheme == .dark
        return VStack(spacing: 12) {
            ProfileMonogram(
                initials: Profile.shared.initials(me: me),
                size: 88,
                fill: Mint.tile(Brand.green, dark),
                ink: Mint.greenText(dark)
            )
            let name = Profile.shared.displayName(me: me)
            if !name.isEmpty {
                Text(name)
                    .font(.title2.weight(.bold))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }

    private func notice(_ key: String) -> some View {
        let dark = scheme == .dark
        return HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(Mint.greenText(dark))
                .accessibilityHidden(true)
            Text(L.t(key)).font(.subheadline)
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Mint.panel(dark)))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Mint.edge(dark), lineWidth: 1))
        .transition(.opacity)
    }

    // MARK: - Account

    private func accountSection(_ me: Me) -> some View {
        ProfileSection(title: L.t(Strings.shared.profile_section_account)) {
            ProfileRow(
                label: L.t(Strings.shared.profile_name),
                value: me.user.displayName?.trimmingCharacters(in: .whitespaces).nilIfEmpty
            ) {
                RowAction(title: L.t(Strings.shared.profile_edit)) {
                    model.startNameEdit(current: me.user.displayName)
                }
            }
            if let email = me.user.email?.nilIfEmpty {
                Divider()
                ProfileRow(label: L.t(Strings.shared.profile_email), value: email)
            }
            if let phone = me.user.phone?.nilIfEmpty {
                Divider()
                ProfileRow(label: L.t(Strings.shared.profile_phone), value: phone)
            }
            Divider()
            ProfileRow(
                label: L.t(Strings.shared.profile_region),
                value: me.household.countryCode.map(countryName)
            ) {
                RowAction(title: L.t(Strings.shared.profile_change), action: onChangeRegion)
            }
            if !model.currency.isEmpty {
                Divider()
                ProfileRow(label: L.t(Strings.shared.profile_currency), value: model.currency) {
                    Text(Money.shared.symbol(currency: model.currency))
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Brand.textMuted)
                }
            }
        }
    }

    // MARK: - Your setup

    private var setupSection: some View {
        ProfileSection(title: L.t(Strings.shared.profile_section_setup)) {
            Button(action: onEditSetup) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L.t(Strings.shared.profile_setup_edit))
                            .font(.body.weight(.medium))
                            .foregroundColor(.primary)
                        Text(L.t(Strings.shared.profile_setup_hint))
                            .font(.footnote)
                            .foregroundColor(Brand.textMuted)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.bold))
                        .foregroundColor(Mint.greenText(scheme == .dark))
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Privacy and consents

    private func privacySection(_ me: Me) -> some View {
        ProfileSection(title: L.t(Strings.shared.profile_section_privacy)) {
            ProfileRow(
                label: L.t(Strings.shared.profile_terms),
                value: me.terms.accepted ? me.terms.version.map { L.t(Strings.shared.profile_terms_accepted, $0) } : nil
            ) {
                RowAction(title: L.t(Strings.shared.profile_read)) { model.readTerms() }
            }
            ErrorText(messageKey: model.readingErrorKey)
            if model.disclaimer != nil {
                Divider()
                ProfileRow(
                    label: L.t(Strings.shared.profile_disclaimer),
                    value: nil,
                    hint: L.t(Strings.shared.profile_disclaimer_hint)
                ) {
                    RowAction(title: L.t(Strings.shared.profile_read)) { model.readDisclaimer() }
                }
            }
            Divider()
            aiRow
        }
    }

    @ViewBuilder
    private var aiRow: some View {
        let status = model.aiStatus
        let on = status?.consented == true
        ProfileRow(
            label: L.t(Strings.shared.profile_ai),
            value: status.map { $0.consented ? L.t(Strings.shared.profile_ai_on, $0.version ?? "") : L.t(Strings.shared.profile_ai_off) },
            hint: L.t(Strings.shared.profile_ai_hint)
        ) {
            if status != nil {
                if model.aiBusy && !model.turningAiOn {
                    ProgressView().frame(minWidth: 44, minHeight: 44)
                } else {
                    RowAction(title: L.t(on ? Strings.shared.profile_ai_turn_off : Strings.shared.profile_ai_turn_on)) {
                        if on { model.confirmingAiOff = true } else { model.startAiOn() }
                    }
                }
            }
        }
        if status != nil && !on {
            Text(L.t(Strings.shared.profile_ai_off_note))
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        if !model.turningAiOn { ErrorText(messageKey: model.aiErrorKey) }
    }

    // MARK: - Sign out and delete

    private var endSection: some View {
        VStack(spacing: 8) {
            Button { account.signOut() } label: {
                Text(L.t(Strings.shared.profile_sign_out))
                    .font(.headline)
                    .tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
            .disabled(account.busy)
            ErrorText(messageKey: account.errorKey)
            Button(role: .destructive) { model.startDelete() } label: {
                Text(L.t(Strings.shared.profile_delete))
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.red)
                    .tappableRow()
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 8)
    }

    // MARK: - Sheets

    private var readingSheet: some View {
        let isDisclaimer = model.reading != nil && model.reading === model.disclaimer
        return NavigationStack {
            ScrollView {
                Text(model.reading?.body ?? "")
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
            }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle(L.t(isDisclaimer ? Strings.shared.profile_disclaimer : Strings.shared.profile_terms))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.t(Strings.shared.action_done)) { model.reading = nil }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var aiOnSheet: some View {
        ScrollView {
            AiConsentPanel(
                title: L.t(Strings.shared.profile_ai_turn_on_title),
                policy: model.policy,
                ticked: $model.aiTicked,
                busy: model.aiBusy,
                errorKey: model.aiErrorKey,
                onAgree: { model.turnAiOn() },
                onNotNow: { model.closeAiSheet() }
            )
            .padding(20)
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(model.aiBusy && model.policy != nil)
    }
}

// MARK: - Pieces

/// A section's title over its panel.
private struct ProfileSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.caption.weight(.bold))
                .foregroundColor(Brand.textMuted)
                .padding(.leading, 4)
                .accessibilityAddTraits(.isHeader)
            MintPanel { content() }
        }
    }
}

/// One fact: what it is, its value, a line of explanation, and what can be done.
private struct ProfileRow<Trailing: View>: View {
    let label: String
    let value: String?
    var hint: String?
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(label).font(.subheadline).foregroundColor(Brand.textMuted)
                if let value {
                    Text(value).font(.body.weight(.medium)).foregroundColor(.primary)
                }
                if let hint {
                    Text(hint).font(.footnote).foregroundColor(Brand.textMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            trailing()
        }
        .frame(minHeight: 44)
    }
}

private extension ProfileRow where Trailing == EmptyView {
    init(label: String, value: String?, hint: String? = nil) {
        self.init(label: label, value: value, hint: hint) { EmptyView() }
    }
}

/// "Edit", "Change", "Read": a small green action at a row's end.
private struct RowAction: View {
    let title: String
    let action: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Mint.greenText(scheme == .dark))
                .tappableArea()
        }
        .buttonStyle(.plain)
    }
}

/// The name, typed and saved; Save reads the shared rule the notice shows.
private struct NameSheet: View {
    @ObservedObject var model: ProfileViewModel
    @ObservedObject var account: OnboardingViewModel
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L.t(Strings.shared.profile_name_hint), text: $model.nameDraft)
                        .textContentType(.name)
                        .textInputAutocapitalization(.words)
                        .submitLabel(.done)
                        .focused($focused)
                        .disabled(model.nameSaving)
                        .onSubmit(save)
                } footer: {
                    ErrorText(messageKey: model.nameErrorKey ?? model.nameBlock)
                }
            }
            .navigationTitle(L.t(Strings.shared.profile_name_edit_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.editingName = false }
                        .disabled(model.nameSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.nameSaving {
                        ProgressView()
                    } else {
                        Button(L.t(Strings.shared.profile_save), action: save)
                            .disabled(!model.canSaveName)
                    }
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled(model.nameSaving)
    }

    private func save() {
        model.saveName { updated in account.replaceMe(updated) }
    }
}

/// "Delete your account?": what goes, the word to type, and the one way to do it.
private struct DeleteAccountSheet: View {
    @ObservedObject var model: ProfileViewModel
    let onDeleted: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(L.t(Strings.shared.profile_delete_body)).font(.subheadline)
                }
                Section {
                    TextField(L.t(Strings.shared.profile_delete_type), text: $model.deleteTyped)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .disabled(model.deleteBusy)
                } footer: {
                    ErrorText(messageKey: model.deleteErrorKey)
                }
                Section {
                    Button(role: .destructive) { model.deleteAccount(onDeleted: onDeleted) } label: {
                        HStack {
                            Spacer()
                            if model.deleteBusy {
                                ProgressView()
                            } else {
                                Text(L.t(Strings.shared.profile_delete_confirm)).font(.headline)
                            }
                            Spacer()
                        }
                        .frame(minHeight: 44)
                    }
                    .disabled(!model.canDelete)
                }
            }
            .navigationTitle(L.t(Strings.shared.profile_delete_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelDelete() }
                        .disabled(model.deleteBusy)
                }
            }
        }
        .interactiveDismissDisabled(model.deleteBusy)
    }
}

private extension String {
    /// Nil for a blank string, so an empty field reads as absent.
    var nilIfEmpty: String? {
        trimmingCharacters(in: .whitespaces).isEmpty ? nil : self
    }
}
