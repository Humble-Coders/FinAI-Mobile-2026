import PhotosUI
import SwiftUI
import SharedLogic
import UIKit
import UniformTypeIdentifiers

/// Importing a statement (#31): which account, which file, consent the first
/// time, the read, and how it ended.
///
/// The waits are the app's one coin loader, with a caption (RootView passes
/// `model.loaderCaption`); this view draws what sits underneath. Every
/// outcome's words come from the shared rules.
struct StatementImportView: View {
    @ObservedObject var model: StatementImportViewModel
    let userId: String
    let onClose: () -> Void
    let onTypeInstead: () -> Void
    let onReview: () -> Void

    /// The account, the file and whether a read was under way, kept for the
    /// scene so the app coming back reads the same file again.
    @SceneStorage("statement_import.state") private var stored = ""
    @State private var pickingFile = false
    @State private var photo: PhotosPickerItem?
    @State private var takingPhoto = false
    /// Held here only — never in scene storage — and cleared once used.
    @State private var password = ""
    @Environment(\.colorScheme) private var scheme
    /// Content has scrolled under the header, which then turns solid.
    @State private var scrolledUnder = false

    var body: some View {
        ZStack {
            MintBackdrop(decoration: model.step == .chooseAccount ? "building.columns.fill" : "doc.text.fill")
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    content
                }
                .modifier(ScrolledUnder(scrolled: $scrolledUnder))
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
        }
        .safeAreaInset(edge: .top, spacing: 0) { header }
        // Save stays in reach however long the statement: a hundred rows must
        // not put the one thing to press at the bottom of a scroll (#55).
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if model.step == .check { checkBar }
        }
        .onAppear { model.bind(userId: userId, restoring: stored) }
        // Not while the camera is up: a full-screen cover makes this view
        // disappear, and unbinding then closed the clients — a photo read
        // before the view came back found none to send with.
        .onDisappear { if !takingPhoto { model.unbind() } }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        .sheet(isPresented: newAccountShown) { NewAccountSheet(model: model) }
        .sheet(isPresented: editingShown) { CorrectionSheet(model: model) }
        .alert(L.t(Strings.shared.import_check_discard_title), isPresented: $model.confirmingDiscard) {
            Button(L.t(Strings.shared.import_check_discard), role: .destructive) { close() }
            Button(L.t(Strings.shared.action_cancel), role: .cancel) {}
        } message: {
            Text(L.t(Strings.shared.import_check_discard_body))
        }
        .fileImporter(isPresented: $pickingFile, allowedContentTypes: [.pdf, .image]) { result in
            if case .success(let url) = result, let copy = try? ImportFiles.adopt(url) {
                model.filePicked(copy)
            }
        }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            photo = nil
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.9),
                   let file = try? ImportFiles.write(jpeg) {
                    model.filePicked(file)
                }
            }
        }
        .fullScreenCover(isPresented: $takingPhoto) {
            CameraPicker(
                onTaken: { data in
                    takingPhoto = false
                    if let file = try? ImportFiles.write(data) { model.filePicked(file) }
                },
                onCancel: { takingPhoto = false }
            )
            .ignoresSafeArea()
        }
    }

    private var newAccountShown: Binding<Bool> {
        Binding(get: { model.newAccount != nil }, set: { if !$0 { model.cancelNewAccount() } })
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { model.editing != nil }, set: { if !$0 { model.cancelEdit() } })
    }

    private func close() {
        model.discard()
        stored = model.snapshot
        onClose()
    }

    private var header: some View {
        MintHeader(title: L.t(Strings.shared.import_title), step: stepNumber, backDisabled: model.working, solid: scrolledUnder) {
            if model.step == .chooseFile {
                model.backToAccount()
            } else if model.step == .check {
                // Leaving loses what was read: asked first (#55).
                model.askDiscard()
            } else if !model.working {
                close()
            }
        }
        .padding(.bottom, 4)
    }

    /// Which of the three dashes is lit: the account, the file, then the read
    /// and its end.
    private var stepNumber: Int {
        switch model.step {
        case .chooseAccount: 1
        case .chooseFile, .consent, .password: 2
        default: 3
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.step {
        case .chooseAccount: accountStep
        case .chooseFile: fileStep
        // The later steps keep their content; the panel gives them the ground.
        case .consent: MintPanel { consentStep }
        case .password: MintPanel { passwordStep }
        case .check: MintPanel { checkStep }
        case .done: MintPanel { doneStep }
        case .failed: MintPanel { failedStep }
        // The coin loader covers these; its caption says which.
        default: Color.clear.frame(height: 1)
        }
    }

    private func title(_ key: String) -> some View {
        Text(L.t(key))
            .font(.title2.weight(.semibold))
            .accessibilityAddTraits(.isHeader)
    }

    /// Which account: every account as a card, chosen by tapping it, then "Add
    /// an account" — the same choices the account list offered, laid out as the
    /// design has them. Continue waits for a real choice.
    private var accountStep: some View {
        VStack(alignment: .leading, spacing: 20) {
            MintTitle(title: L.t(Strings.shared.import_account_title), subtitle: nil)
            MintPanel {
                if let key = model.accountsErrorKey {
                    ErrorText(messageKey: key)
                    Button(L.t(Strings.shared.import_try_again)) { model.loadAccounts() }
                } else if model.accountsLoading {
                    ProgressView().frame(maxWidth: .infinity).padding(24)
                } else {
                    if model.accounts.isEmpty {
                        Text(L.t(Strings.shared.manual_entry_accounts_empty)).foregroundColor(Brand.textMuted)
                    }
                    ForEach(Array(model.accounts.enumerated()), id: \.element.id) { index, account in
                        let chosen = account.id == model.accountId
                        Button { model.chooseAccount(account.id) } label: {
                            ChoiceCardLabel(
                                symbol: "building.columns.fill",
                                accent: Mint.accountTints[index % Mint.accountTints.count],
                                title: account.name,
                                detail: Self.detail(account),
                                selected: chosen
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(chosen ? .isSelected : [])
                    }
                    Button { model.openNewAccount() } label: {
                        ChoiceCardLabel(symbol: "plus", accent: Brand.green, title: L.t(Strings.shared.manual_entry_account_add))
                    }
                    .buttonStyle(.plain)
                }
                // Not optional and not defaulted: Continue waits for a real choice.
                GradientButton(title: L.t(Strings.shared.action_continue), enabled: model.canContinueFromAccount) {
                    model.continueFromAccount()
                }
                .padding(.top, 4)
            }
        }
    }

    /// "Savings · CAD": the kind, then the currency — what the account list shows.
    static func detail(_ account: Account) -> String? {
        let parts = [account.kind.labelKey.map { L.t($0) }, account.currency.isEmpty ? nil : account.currency]
            .compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// The three ways in, what happens to the file, and which account it is for.
    private var fileStep: some View {
        let dark = scheme == .dark
        return VStack(alignment: .leading, spacing: 20) {
            MintTitle(title: L.t(Strings.shared.import_file_title), subtitle: L.t(Strings.shared.import_file_subtitle))
            MintPanel {
                Button { pickingFile = true } label: {
                    ChoiceCardLabel(
                        symbol: "doc.text.fill", accent: Brand.green,
                        title: L.t(Strings.shared.import_pick_file_title),
                        detail: nil
                    )
                }
                .buttonStyle(.plain)
                PhotosPicker(selection: $photo, matching: .images) {
                    ChoiceCardLabel(
                        symbol: "photo.fill", accent: Brand.blue,
                        title: L.t(Strings.shared.import_pick_photo),
                        detail: nil
                    )
                }
                .buttonStyle(.plain)
                if CameraPicker.isAvailable {
                    Button { takingPhoto = true } label: {
                        ChoiceCardLabel(
                            symbol: "camera.fill", accent: Mint.orange,
                            title: L.t(Strings.shared.import_take_photo),
                            detail: L.t(Strings.shared.import_take_photo_detail),
                            tinted: true
                        )
                    }
                    .buttonStyle(.plain)
                }
                // The true thing, said plainly: read here, never uploaded.
                HStack(alignment: .center, spacing: 14) {
                    IconTile(symbol: "lock.fill", accent: Brand.green, size: 40, iconSize: 18)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L.t(Strings.shared.import_on_device))
                            .font(.footnote)
                            .foregroundColor(Brand.textMuted)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Brand.green.opacity(0.08)))
                Text(L.t(Strings.shared.import_selected_account))
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(Brand.textMuted)
                    .padding(.top, 4)
                    .padding(.leading, 4)
                Button { model.backToAccount() } label: {
                    ChoiceCardLabel(
                        symbol: "building.columns.fill", accent: Brand.green,
                        title: model.account?.name ?? L.t(Strings.shared.manual_entry_account_placeholder),
                        detail: model.account.flatMap { Self.detail($0) }
                    ) {
                        Text(L.t(Strings.shared.import_change_account))
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Mint.greenText(dark))
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var consentStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_consent_title)
            ErrorText(messageKey: model.consentErrorKey)
            // The server's words, so they can change without an app release.
            if let policy = model.policy {
                Text(policy.body)
                    .font(.subheadline)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Brand.sheet, in: RoundedRectangle(cornerRadius: 16))
                Toggle(L.t(Strings.shared.import_consent_agree), isOn: $model.consentTicked)
                    .toggleStyle(CheckboxStyle())
            }
            GradientButton(
                title: L.t(Strings.shared.import_consent_continue),
                enabled: model.canAgree,
                busy: model.consentBusy
            ) { model.agree() }
            Button(L.t(Strings.shared.import_consent_not_now)) { model.declineConsent() }
                .frame(maxWidth: .infinity)
        }
    }

    private var passwordStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L.t(model.passwordWrong ? Strings.shared.statement_password_wrong : Strings.shared.statement_password_prompt))
            SecureField(L.t(Strings.shared.import_password_label), text: $password)
                .textContentType(.password)
                .submitLabel(.go)
                .onSubmit(submitPassword)
                .padding(.horizontal, 16)
                .frame(minHeight: 56)
                .fieldFrame(focused: false, isError: model.passwordWrong)
            GradientButton(title: L.t(Strings.shared.import_password_submit), enabled: !password.isEmpty) {
                submitPassword()
            }
            Button(L.t(Strings.shared.import_choose_another)) { model.chooseAnotherFile() }
                .frame(maxWidth: .infinity)
        }
    }

    private func submitPassword() {
        let value = password
        password = ""
        model.submitPassword(value)
    }

    private var doneStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_done_title)
            VStack(alignment: .leading, spacing: 8) {
                ForEach(model.summary, id: \.self) { Text($0) }
            }
            .onAppear { AccessibilityNotification.Announcement(model.summary.joined(separator: ". ")).post() }

            // What it actually read. "Imported 24" is a claim the person
            // cannot check, and the one question they have is whether the
            // categories are right.
            importedRows

            // Straight into the review queue when rows are waiting (#32).
            if model.needsReview > 0 {
                Text(L.t(Strings.shared.import_review_now)).foregroundColor(Brand.textMuted)
                GradientButton(title: L.t(Strings.shared.review_entry)) { review() }
                Button { close() } label: {
                    Text(L.t(Strings.shared.import_done)).font(.headline).tappableRow(minHeight: 52)
                }
                .buttonStyle(.bordered)
                .tint(.primary)
            } else {
                GradientButton(title: L.t(Strings.shared.import_done)) { close() }
            }
        }
    }

    private var failedStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_failed_title)
            if let message = model.problemMessage {
                Text(message)
                    .onAppear { AccessibilityNotification.Announcement(message).post() }
            }
            if model.offersRetry {
                GradientButton(title: L.t(Strings.shared.import_try_again)) { model.retry() }
            }
            if model.offersManualEntry {
                if model.offersRetry {
                    Button { typeInstead() } label: {
                        Text(L.t(Strings.shared.import_type_instead)).font(.headline).tappableRow(minHeight: 52)
                    }
                    .buttonStyle(.bordered)
                    .tint(.primary)
                } else {
                    GradientButton(title: L.t(Strings.shared.import_type_instead)) { typeInstead() }
                }
            }
            Button(L.t(Strings.shared.import_choose_another)) { model.chooseAnotherFile() }
                .frame(maxWidth: .infinity)
            if model.offersDiagnostics { diagnosticsOffer }
            if let thanks = model.diagnosticsThanks {
                Text(thanks).foregroundColor(Brand.textMuted)
            }
        }
    }

    private func review() {
        model.discard()
        stored = model.snapshot
        onReview()
    }

    private func typeInstead() {
        model.discard()
        stored = model.snapshot
        onTypeInstead()
    }

    /// Asked per import, unticked, and only here — never after a clean import.
    private var diagnosticsOffer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L.t(Strings.shared.import_diagnostics_title)).font(.headline)
            Text(L.t(Strings.shared.import_diagnostics_body)).font(.subheadline)
            Toggle(L.t(Strings.shared.import_diagnostics_agree), isOn: $model.diagnosticsTicked)
                .toggleStyle(CheckboxStyle())
            Button { model.sendDiagnostics() } label: {
                Text(L.t(Strings.shared.import_diagnostics_send)).font(.headline).tappableRow(minHeight: 48)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
            .disabled(!model.canSendDiagnostics)
        }
        .padding(16)
        .background(Brand.sheet, in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - What the import read

    /// The rows the import produced, grouped by the day they fell on.
    ///
    /// Arranged by shared `ImportedRows` so Android shows the same list in the
    /// same order; this draws it. A failure to load leaves the import
    /// succeeded — the summary above still stands, and the offer to try again
    /// is a line, not an error screen.
    @ViewBuilder
    private var importedRows: some View {
        if model.importedLoading {
            ProgressView().frame(maxWidth: .infinity).padding(.vertical, 8)
        } else if model.importedErrorKey != nil {
            VStack(alignment: .leading, spacing: 8) {
                Text(L.t(Strings.shared.import_extracted_failed)).foregroundColor(Brand.textMuted)
                Button { model.reloadImported() } label: {
                    Text(L.t(Strings.shared.import_extracted_retry)).font(.headline).tappableRow(minHeight: 52)
                }
                .buttonStyle(.bordered)
                .tint(.primary)
            }
        } else if !model.imported.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(L.t(Strings.shared.import_extracted_title)).font(.subheadline.weight(.semibold))
                importedTotals
                ForEach(model.importedDays, id: \.date) { day in
                    Text(model.dateLabel(day.date))
                        .font(.caption)
                        .foregroundColor(Brand.textMuted)
                        .padding(.top, 6)
                    VStack(spacing: 0) {
                        ForEach(Array(day.rows.enumerated()), id: \.element.id) { index, row in
                            // Each opens the editor, where a category is fixed (#55).
                            importedRow(row, description: model.rowDescription(row), hint: model.editHint(row)) {
                                if let label = model.categoryLabel(row) {
                                    note(label, warning: !model.isFiled(row))
                                }
                            }
                            if index != day.rows.count - 1 {
                                Divider().overlay(Brand.border)
                            }
                        }
                    }
                    .background(Brand.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
    }

    /// Money out, money in, and how many still want a person.
    @ViewBuilder
    private var importedTotals: some View {
        // One line when it fits; stacked when the text is large.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 6) { importedChips }
            VStack(alignment: .leading, spacing: 6) { importedChips }
        }
    }

    @ViewBuilder
    private var importedChips: some View {
        // Each omitted when there is none, rather than shown as zero: "0.00 in"
        // reads as a fact about the statement rather than an absence of rows.
        if let out = model.totalOut { chip(L.t(Strings.shared.import_extracted_out, out), warning: false) }
        if let money = model.totalIn { chip(L.t(Strings.shared.import_extracted_in, money), warning: false) }
        if let waiting = model.waitingLabel { chip(waiting, warning: true) }
    }

    @ViewBuilder
    private var checkChips: some View {
        chip(model.checkCount, warning: false)
        if let out = model.checkTotalOut { chip(L.t(Strings.shared.import_extracted_out, out), warning: false) }
        if let money = model.checkTotalIn { chip(L.t(Strings.shared.import_extracted_in, money), warning: false) }
    }

    private func chip(_ text: String, warning: Bool) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundColor(warning ? Brand.amber : Brand.textMuted)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(warning ? Brand.amber.opacity(0.12) : Brand.surface)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    /// One row of a statement: its name, a note under it, and its amount.
    /// Tapping it opens the editor — before saving, to fix what was read;
    /// after, to fix where it was filed.
    private func importedRow<Note: View>(
        _ row: SharedLogic.Transaction,
        description: String,
        hint: String,
        @ViewBuilder note: () -> Note
    ) -> some View {
        Button { model.edit(row.id) } label: {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.titleOf(row)).font(.body).lineLimit(1).truncationMode(.tail)
                    note()
                }
                Spacer()
                Text(model.amountLabel(row))
                    .font(.body.weight(.semibold))
                    // Green for money in; money out stays plain. Colouring both
                    // makes every row shout and the direction stops registering.
                    .foregroundColor(row.direction == .credit ? Brand.green : .primary)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Brand.textMuted)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // One announcement per row: the name, the note and the amount, rather
        // than three separate stops.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
        .accessibilityHint(hint)
        .accessibilityAddTraits(.isButton)
    }

    /// A small label under a row's name: what it was filed as, or a doubt.
    private func note(_ text: String, warning: Bool) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundColor(warning ? Brand.amber : Brand.textMuted)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(warning ? Brand.amber.opacity(0.12) : Brand.surfaceField)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }

    // MARK: - Checking before saving (#55)

    /// Every row the statement produced, before any of it is saved. A row opens
    /// the editor to fix what was read, or to remove it; Save and Discard sit
    /// in the bar beneath (`checkBar`).
    private var checkStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_check_title)
            if model.readAgain {
                HStack(alignment: .center) {
                    Text(L.t(Strings.shared.import_check_read_again))
                        .font(.footnote)
                        .foregroundColor(Brand.amber)
                    Spacer()
                    Button(L.t(Strings.shared.import_check_got_it)) { model.dismissReadAgain() }
                        .font(.footnote.weight(.semibold))
                }
                .padding(12)
                .background(Brand.amber.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .onAppear { AccessibilityNotification.Announcement(L.t(Strings.shared.import_check_read_again)).post() }
            }
            Text(L.t(Strings.shared.import_check_subtitle)).foregroundColor(Brand.textMuted)

            if model.noneLeft {
                Text(L.t(Strings.shared.import_check_none_left))
            } else {
                // One line when it fits; stacked when the text is large.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 6) { checkChips }
                    VStack(alignment: .leading, spacing: 6) { checkChips }
                }
                if let breakdown = model.checkBreakdown { breakdownCard(breakdown) }
                if let question = model.balanceQuestion { balanceCard(question) }
                ForEach(model.checkDays, id: \.date) { day in
                    Text(model.dateLabel(day.date))
                        .font(.caption)
                        .foregroundColor(Brand.textMuted)
                        .padding(.top, 6)
                    VStack(spacing: 0) {
                        ForEach(Array(day.rows.enumerated()), id: \.element.id) { index, row in
                            importedRow(row, description: model.checkRowDescription(row), hint: model.checkRowHint(row)) {
                                HStack(spacing: 6) {
                                    if let category = model.checkCategoryLabel(row) {
                                        note(category, warning: row.categoryId == nil)
                                    }
                                    if let key = model.checkNoteKey(row) {
                                        note(L.t(key), warning: key == Strings.shared.import_check_doubt)
                                    }
                                }
                            }
                            if index != day.rows.count - 1 {
                                Divider().overlay(Brand.border)
                            }
                        }
                    }
                    .background(Brand.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
    }

    /// The statement's closing balance, read on the phone, and whether it should
    /// become the linked debt's balance (#76). Unticked until the person says yes.
    private func balanceCard(_ question: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L.t(Strings.shared.import_balance_title))
                .font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Toggle(isOn: $model.updateBalance) {
                Text(question).font(.subheadline)
            }
            .toggleStyle(.switch)
        }
        .padding(14)
        .background(Brand.surface)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// Where the statement's money goes before it is saved: money out and in by
    /// category, and what only moved between the person's own accounts — the
    /// way Home will count it. Follows every category changed on the rows.
    private func breakdownCard(_ breakdown: Breakdown) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L.t(Strings.shared.import_check_breakdown_title))
                .font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            breakdownSide(L.t(Strings.shared.import_check_money_out), total: breakdown.totalOut, slices: breakdown.moneyOut, isIn: false)
            breakdownSide(L.t(Strings.shared.import_check_money_in), total: breakdown.totalIn, slices: breakdown.moneyIn, isIn: true)
            if let between = breakdown.betweenAccounts {
                Divider().overlay(Brand.border)
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L.t(Strings.shared.import_check_between)).font(.subheadline)
                    }
                    Spacer()
                    Text(model.checkMoneyLabel(between)).font(.subheadline)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(14)
        .background(Brand.surface)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// One side of the breakdown: its total, then each category's share. Nothing when empty.
    @ViewBuilder
    private func breakdownSide(_ title: String, total: String?, slices: [Slice], isIn: Bool) -> some View {
        if let total {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(title).font(.footnote.weight(.semibold))
                    Spacer()
                    Text(model.checkMoneyLabel(total))
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(isIn ? Brand.green : .primary)
                }
                .accessibilityElement(children: .combine)
                ForEach(Array(slices.enumerated()), id: \.offset) { _, slice in
                    HStack {
                        Text(slice.name ?? L.t(Strings.shared.import_extracted_uncategorised))
                            .font(.caption)
                            .foregroundColor(slice.categoryId == nil ? Brand.amber : Brand.textMuted)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Spacer()
                        Text(model.checkMoneyLabel(slice.amount)).font(.caption)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    /// Save, or leave without saving — pinned under the list while checking.
    private var checkBar: some View {
        VStack(spacing: 4) {
            ErrorText(messageKey: model.saveErrorKey)
            GradientButton(title: model.saveCheckedLabel, enabled: model.canSaveChecked) { model.saveChecked() }
            Button(L.t(Strings.shared.import_check_discard)) { model.askDiscard() }
                .frame(maxWidth: .infinity)
                .tappableRow(minHeight: 44)
        }
        .frame(maxWidth: 560)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }
}

/// A box nothing pre-ticks, with its label as the tap target. Shared by every
/// screen that asks for consent.
struct CheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(spacing: 12) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundColor(.primary)
                    .accessibilityHidden(true)
                configuration.label.foregroundColor(.primary)
                Spacer()
            }
            .tappableRow(minHeight: 44)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(configuration.isOn ? [.isSelected] : [])
    }
}
