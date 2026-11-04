import SharedLogic
import SwiftUI
import UIKit

// The later steps of Split expense: the people, who had what, the review with
// its messages, and the splits made before. Android's `SplitSteps.kt`.

// MARK: - 3. The people

/// The people saved before, ticked onto this bill, and the two ways to add
/// someone new: from the phone's contacts, or typed in. A list, so a saved
/// person can be swiped away.
struct SplitPeopleStep: View {
    @ObservedObject var model: SplitViewModel
    let onBack: () -> Void

    @State private var pickingContact = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        SplitStepFrame(model: model, decoration: "person.2.fill", list: true, onBack: onBack) {
            List {
                Section {
                    MintTitle(title: L.t(Strings.shared.split_select_people), subtitle: L.t(Strings.shared.split_select_people_detail))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                    SplitSearchField(text: $model.search)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    Button { pickingContact = true } label: {
                        ChoiceCardLabel(symbol: "person.crop.circle.badge.plus", accent: Brand.blue, title: L.t(Strings.shared.split_from_contacts)) {
                            if model.pickingSaving { ProgressView() } else { chevron }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(model.pickingSaving || model.atPeopleLimit)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    Button { model.startAddPerson() } label: {
                        ChoiceCardLabel(symbol: "plus", accent: Brand.green, title: L.t(Strings.shared.split_add_new_person))
                    }
                    .buttonStyle(.plain)
                    .disabled(model.atPeopleLimit)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }
                .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))

                Section {
                    if let key = model.contactsErrorKey {
                        VStack(alignment: .leading, spacing: 8) {
                            ErrorText(messageKey: key)
                            Button(L.t(Strings.shared.action_retry)) { model.loadContacts() }
                                .font(.subheadline.weight(.semibold))
                                .frame(minHeight: 44)
                        }
                        .listRowBackground(Mint.panel(dark))
                    }
                    if model.contactsLoading {
                        ProgressView()
                            .frame(maxWidth: .infinity, minHeight: 60)
                            .listRowBackground(Mint.panel(dark))
                    } else if model.contactsEmpty {
                        Text(L.t(Strings.shared.split_no_saved_people))
                            .font(.subheadline)
                            .foregroundColor(Brand.textMuted)
                            .listRowBackground(Mint.panel(dark))
                    }
                    ForEach(Array(model.shownContacts.enumerated()), id: \.element.id) { index, contact in
                        contactRow(contact, tint: Monogram.tint(index + 1))
                            .listRowBackground(Mint.panel(dark))
                    }
                } header: {
                    Text(L.t(Strings.shared.split_saved_people))
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Brand.textMuted)
                        .textCase(nil)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .frame(maxWidth: 600)
            .frame(maxWidth: .infinity)
            .refreshable { model.loadContacts() }
        } bar: {
            Text(SplitExpense.shared.selectedLine(count: Int32(model.others), locale: model.locale))
                .font(.subheadline.weight(.medium))
                .foregroundColor(Brand.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            GradientButton(title: L.t(Strings.shared.split_continue), enabled: model.canContinueFromPeople) {
                dismissKeyboard()
                model.continueFromPeople()
            }
        }
        .background(ContactPicker(isPresented: $pickingContact) { name, phone in
            model.pickedFromContacts(name: name, phone: phone)
        }.frame(width: 0, height: 0))
        .sheet(isPresented: $model.addingPerson) { SplitAddPersonSheet(model: model) }
        .alert(
            model.confirmingContact?.name ?? "",
            isPresented: Binding(get: { model.confirmingContact != nil }, set: { if !$0 { model.confirmingContact = nil } }),
            presenting: model.confirmingContact
        ) { contact in
            Button(L.t(Strings.shared.split_person_delete, contact.name), role: .destructive) {
                model.confirmingContact = nil
                model.deleteContact(contact)
            }
            Button(L.t(Strings.shared.action_cancel), role: .cancel) { model.confirmingContact = nil }
        } message: { contact in
            Text(contact.phone + "\n\n" + L.t(Strings.shared.split_person_delete_confirm))
        }
    }

    private var chevron: some View {
        Image(systemName: "chevron.right")
            .font(.footnote.weight(.semibold))
            .foregroundColor(Brand.textMuted)
            .accessibilityHidden(true)
    }

    private func contactRow(_ contact: SplitContact, tint: Color) -> some View {
        let chosen = model.isChosen(contact)
        let blocked = !chosen && model.atPeopleLimit
        return Button { model.toggle(contact) } label: {
            HStack(spacing: 12) {
                Monogram(initials: SplitExpense.shared.initials(name: contact.name), size: 40, tint: tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(contact.name)
                        .font(.body.weight(.medium))
                        .foregroundColor(.primary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Text(contact.phone)
                        .font(.footnote)
                        .foregroundColor(Brand.textMuted)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                RadioMark(chosen: chosen)
                Button { model.confirmingContact = contact } label: {
                    Image(systemName: "trash")
                        .foregroundColor(Brand.textMuted)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHidden(true)
            }
            .tappableRow(minHeight: 52)
        }
        .buttonStyle(.plain)
        .disabled(blocked)
        .accessibilityLabel(contact.name)
        .accessibilityValue(contact.phone)
        .accessibilityAddTraits(chosen ? .isSelected : [])
        .accessibilityAction(named: L.t(Strings.shared.split_person_delete, contact.name)) { model.confirmingContact = contact }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) { model.confirmingContact = contact } label: {
                Label(L.t(Strings.shared.split_person_delete, contact.name), systemImage: "trash")
            }
        }
    }
}

/// Searching the saved people by name or number.
private struct SplitSearchField: View {
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "magnifyingglass").foregroundColor(Brand.textMuted).accessibilityHidden(true)
            TextField("", text: $text, prompt: Text(L.t(Strings.shared.split_search_people)).foregroundColor(Brand.textMuted.opacity(0.6)))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($focused)
                .tint(Brand.green)
                .accessibilityLabel(L.t(Strings.shared.split_search_people))
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 52)
        .fieldFrame(focused: focused, isError: false)
        .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
        .onTapGesture { focused = true }
    }
}

/// Someone new: a name and the number WhatsApp reaches them on.
struct SplitAddPersonSheet: View {
    @ObservedObject var model: SplitViewModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    WizardField(
                        label: L.t(Strings.shared.split_person_name),
                        autocapitalization: .words,
                        submitLabel: .next,
                        leading: "person.fill",
                        text: $model.personName
                    )
                    VStack(alignment: .leading, spacing: 6) {
                        WizardField(
                            label: L.t(Strings.shared.split_person_phone),
                            keyboard: .phonePad,
                            isError: model.personErrorKey != nil,
                            leading: "phone.fill",
                            text: $model.personPhone
                        )
                        Text(L.t(Strings.shared.split_person_phone_hint))
                            .font(.footnote)
                            .foregroundColor(Brand.textMuted)
                    }
                    ErrorText(messageKey: model.personErrorKey)
                    GradientButton(
                        title: L.t(Strings.shared.split_person_save),
                        enabled: model.canSavePerson,
                        busy: model.personSaving
                    ) {
                        dismissKeyboard()
                        model.savePerson()
                    }
                }
                .frame(maxWidth: 560)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(L.t(Strings.shared.split_add_new_person))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelAddPerson() }
                        .disabled(model.personSaving)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(model.personSaving)
    }
}

// MARK: - 4. Who had what

/// Each item and the people who had it, what is still nobody's, and each
/// person's share as it stands.
struct SplitAssignStep: View {
    @ObservedObject var model: SplitViewModel
    let onBack: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        SplitStepFrame(model: model, decoration: "list.bullet.rectangle.fill", onBack: onBack) {
            MintTitle(title: L.t(Strings.shared.split_assign_title), subtitle: L.t(Strings.shared.split_assign_detail))
            totals(dark)
            ForEach(Array(model.items.indices), id: \.self) { index in
                SplitAssignItem(model: model, index: index, dark: dark)
            }
            if model.canAddItem {
                Button { model.addItem() } label: {
                    HStack(spacing: 12) {
                        IconTile(symbol: "plus", accent: Mint.greenText(dark), size: 40, iconSize: 18)
                        Text(L.t(Strings.shared.split_add_item)).font(.headline).foregroundColor(.primary)
                        Spacer(minLength: 0)
                    }
                    .tappableRow(minHeight: 52)
                }
                .buttonStyle(.plain)
            }
            if let shares = model.previewShares {
                MintPanel {
                    Text(L.t(Strings.shared.split_summary))
                        .font(.headline)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(Array(model.people.enumerated()), id: \.offset) { index, person in
                        SplitShareLine(
                            initials: model.initials(person),
                            label: model.label(person),
                            share: shares.indices.contains(index) ? shares[index] : nil,
                            detail: nil,
                            tint: Monogram.tint(index)
                        )
                    }
                }
            }
        } bar: {
            SplitNotice(text: model.saveNotice)
            GradientButton(title: L.t(Strings.shared.split_continue), enabled: model.canContinueFromAssign) {
                dismissKeyboard()
                model.continueFromAssign()
            }
        }
    }

    private func totals(_ dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                figure(Strings.shared.split_assigned, model.assignedAmount, tint: Mint.greenText(dark))
                figure(Strings.shared.split_unassigned, model.unassignedAmount ?? "0", tint: model.unassignedAmount == nil ? .primary : Brand.amber)
            }
            if let left = model.unassignedAmount {
                Text(L.t(Strings.shared.split_unassigned_warning, model.money(left)))
                    .font(.footnote)
                    .foregroundColor(Brand.amber)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func figure(_ key: String, _ amount: String?, tint: Color) -> some View {
        let value = amount.map { model.money($0) } ?? ""
        return VStack(alignment: .leading, spacing: 4) {
            Text(L.t(key)).font(.footnote.weight(.medium)).foregroundColor(Brand.textMuted).lineLimit(1)
            Text(value).font(.headline).foregroundColor(tint).lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Mint.panel(scheme == .dark)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Mint.edge(scheme == .dark), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L.t(key))
        .accessibilityValue(value)
    }
}

/// One item: its name and price, still editable, and a chip for each person.
private struct SplitAssignItem: View {
    @ObservedObject var model: SplitViewModel
    let index: Int
    let dark: Bool

    var body: some View {
        MintPanel {
            SplitItemFields(
                label: L.t(Strings.shared.split_item_name, String(index + 1)),
                symbol: model.symbol,
                placeholder: model.amountPlaceholder,
                name: Binding(get: { model.itemName(index) }, set: { model.setItemName(index, $0) }),
                amount: Binding(get: { model.itemAmount(index) }, set: { model.setItemAmount(index, $0) })
            )
            FlowLayout(spacing: 8) {
                chip(
                    title: L.t(Strings.shared.split_assign_everyone),
                    initials: nil,
                    tint: Brand.green,
                    on: model.isEveryone(item: index)
                ) { model.everyone(item: index) }
                ForEach(Array(model.people.enumerated()), id: \.offset) { person, entry in
                    chip(
                        title: model.label(entry),
                        initials: model.initials(entry),
                        tint: Monogram.tint(person),
                        on: model.isAssigned(item: index, person: person)
                    ) { model.toggle(item: index, person: person) }
                }
            }
        }
    }

    private func chip(title: String, initials: String?, tint: Color, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let initials {
                    Monogram(initials: initials, size: 26, tint: tint)
                } else {
                    Image(systemName: "person.3.fill").font(.caption).accessibilityHidden(true)
                }
                Text(title).font(.subheadline.weight(.medium)).lineLimit(1).truncationMode(.tail)
                if on {
                    Image(systemName: "checkmark").font(.caption.weight(.bold)).accessibilityHidden(true)
                }
            }
            .foregroundColor(on ? Brand.onGreen : .primary)
            .padding(.leading, initials == nil ? 12 : 6)
            .padding(.trailing, 12)
            .frame(minHeight: 44)
            .background(Capsule().fill(on ? Brand.green : Mint.card(dark)))
            .overlay(Capsule().stroke(on ? Brand.green : Mint.edge(dark), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// A person and their share: the line the summaries are made of.
struct SplitShareLine: View {
    let initials: String
    let label: String
    let share: String?
    let detail: String?
    let tint: Color
    var accessibility: String?

    var body: some View {
        HStack(spacing: 12) {
            Monogram(initials: initials, size: 40, tint: tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.body.weight(.medium)).lineLimit(1).truncationMode(.tail)
                if let detail {
                    Text(detail).font(.footnote).foregroundColor(Brand.textMuted).lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            if let share {
                Text(share).font(.headline).monospacedDigit().lineLimit(1).layoutPriority(1)
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibility ?? [label, share].compactMap { $0 }.joined(separator: ", "))
    }
}

// MARK: - 5. The review

/// The bill and each person's share. Before saving, the shares as they will
/// be; after, the saved split's — the server's — with WhatsApp, paid, and the
/// message for a group.
struct SplitReviewStep: View {
    @ObservedObject var model: SplitViewModel
    let onBack: () -> Void
    let onDone: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        SplitStepFrame(
            model: model,
            decoration: "checkmark.seal.fill",
            showsBar: !model.savedFromHistory,
            onBack: onBack
        ) {
            MintTitle(title: L.t(Strings.shared.split_review_title), subtitle: L.t(Strings.shared.split_review_detail))
            billCard(dark)
            if let saved = model.saved {
                savedSummary(saved)
                ErrorText(messageKey: model.reviewErrorKey)
                if let message = model.groupMessage { messagePreview(message, dark) }
            } else {
                draftSummary
            }
        } bar: {
            if model.saved != nil {
                GradientButton(title: L.t(Strings.shared.action_done), action: onDone)
            } else {
                SplitNotice(text: model.saveNotice)
                ErrorText(messageKey: model.saveErrorKey)
                GradientButton(title: L.t(Strings.shared.split_save), enabled: model.canSave, busy: model.saving) {
                    model.save()
                }
            }
        }
        .alert(L.t(Strings.shared.split_send_whatsapp), isPresented: noWhatsAppShown) {
            Button(L.t(Strings.shared.split_copy_message)) {
                if let person = model.noWhatsAppFor, let message = model.message(for: person) { model.copy(message) }
                model.noWhatsAppFor = nil
            }
            Button(L.t(Strings.shared.action_cancel), role: .cancel) { model.noWhatsAppFor = nil }
        } message: {
            Text(L.t(Strings.shared.split_no_whatsapp))
        }
    }

    private var noWhatsAppShown: Binding<Bool> {
        Binding(get: { model.noWhatsAppFor != nil }, set: { if !$0 { model.noWhatsAppFor = nil } })
    }

    // MARK: The bill

    private func billCard(_ dark: Bool) -> some View {
        let saved = model.saved
        let title = saved?.title ?? model.title
        let date = saved == nil ? model.dateLabel : model.savedDateLabel
        let total: String? = saved.map {
            Money.shared.format(amount: $0.total, currency: model.savedWords.currency, locale: model.locale)
        } ?? model.billTotal.map { model.money($0) }
        let byItems = saved.map { $0.mode == SplitMode.items.wire } ?? (model.mode == .items)
        let people = saved?.participants.count ?? model.people.count
        let way = L.t(byItems ? Strings.shared.split_by_items : Strings.shared.split_equally)
        let count = L.t(Strings.shared.split_people_count, String(people))
        return HStack(alignment: .top, spacing: 14) {
            IconTile(symbol: byItems ? "list.bullet.rectangle.fill" : "divide.circle.fill", accent: Mint.greenText(dark))
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline).lineLimit(1).truncationMode(.tail)
                if let date { Text(date).font(.footnote).foregroundColor(Brand.textMuted).lineLimit(1) }
                HStack(spacing: 8) {
                    Text(way)
                    Text(count)
                }
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
                .lineLimit(1)
            }
            Spacer(minLength: 8)
            if let total {
                Text(total).font(.title3.weight(.bold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Mint.panel(dark)))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Mint.edge(dark), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, date, total, way, count].compactMap { $0 }.joined(separator: ", "))
    }

    // MARK: Before saving

    private var draftSummary: some View {
        let shares = model.previewShares
        return MintPanel {
            Text(L.t(Strings.shared.split_summary)).font(.headline).accessibilityAddTraits(.isHeader)
            ForEach(Array(model.people.enumerated()), id: \.offset) { index, person in
                SplitShareLine(
                    initials: model.initials(person),
                    label: model.label(person),
                    share: shares.flatMap { $0.indices.contains(index) ? $0[index] : nil },
                    detail: nil,
                    tint: Monogram.tint(index)
                )
            }
        }
    }

    // MARK: Saved

    private func savedSummary(_ saved: Split) -> some View {
        MintPanel {
            Text(L.t(Strings.shared.split_summary)).font(.headline).accessibilityAddTraits(.isHeader)
            ForEach(model.shareRows, id: \.index) { row in
                VStack(alignment: .leading, spacing: 8) {
                    SplitShareLine(
                        initials: row.initials,
                        label: row.label,
                        share: row.share,
                        detail: row.detail,
                        tint: Monogram.tint(Int(row.index)),
                        accessibility: row.accessibility
                    )
                    if !row.isSelf, let person = model.participant(at: Int(row.index)) {
                        actions(for: person)
                    }
                }
                if Int(row.index) < saved.participants.count - 1 { Divider() }
            }
        }
    }

    private func actions(for person: SplitParticipant) -> some View {
        HStack(spacing: 8) {
            if model.whatsAppURL(for: person) != nil {
                Button { model.sendOnWhatsApp(person) } label: {
                    Label(L.t(Strings.shared.split_send_whatsapp), systemImage: "paperplane.fill")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .tappableRow()
                }
                .buttonStyle(.borderedProminent)
                .tint(Brand.greenDeep)
                .accessibilityLabel(L.t(Strings.shared.split_send_to, person.name))
            }
            Button { model.settle(person) } label: {
                Group {
                    if model.settlingId == person.id {
                        ProgressView()
                    } else {
                        Label(
                            L.t(person.settled ? Strings.shared.split_paid : Strings.shared.split_mark_paid),
                            systemImage: person.settled ? "checkmark.circle.fill" : "circle"
                        )
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    }
                }
                .tappableRow()
            }
            .buttonStyle(.bordered)
            .tint(person.settled ? Brand.greenDeep : .primary)
            .disabled(model.settlingId != nil)
            .accessibilityLabel(L.t(person.settled ? Strings.shared.split_paid : Strings.shared.split_mark_paid))
            .accessibilityHint(person.name)
            .accessibilityAddTraits(person.settled ? .isSelected : [])
        }
    }

    private func messagePreview(_ message: String, _ dark: Bool) -> some View {
        MintPanel {
            Text(L.t(Strings.shared.split_message_preview)).font(.headline).accessibilityAddTraits(.isHeader)
            Text(message)
                .font(.subheadline)
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Mint.card(dark)))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Mint.edge(dark), lineWidth: 1))
                .textSelection(.enabled)
            Button { model.copyGroupMessage() } label: {
                Label(
                    L.t(model.copied ? Strings.shared.split_copied : Strings.shared.split_copy_message),
                    systemImage: model.copied ? "checkmark" : "doc.on.doc"
                )
                .font(.subheadline.weight(.semibold))
                .tappableRow()
            }
            .buttonStyle(.bordered)
            .tint(.primary)
        }
    }
}

// MARK: - 6. Your splits

/// The splits made before, newest first: what each was, when, and what is
/// still to come. Open one to send again or mark paid; swipe to delete.
struct SplitHistoryStep: View {
    @ObservedObject var model: SplitViewModel
    let onBack: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        SplitStepFrame(model: model, decoration: "clock.arrow.circlepath", list: true, onBack: onBack) {
            List {
                MintTitle(title: L.t(Strings.shared.split_history), subtitle: nil)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                if let key = model.historyErrorKey {
                    VStack(alignment: .leading, spacing: 8) {
                        ErrorText(messageKey: key)
                        Button(L.t(Strings.shared.action_retry)) { model.loadHistory() }
                            .font(.subheadline.weight(.semibold))
                            .frame(minHeight: 44)
                    }
                    .listRowBackground(Mint.panel(dark))
                }
                if model.historyEmpty {
                    Text(L.t(Strings.shared.split_history_empty))
                        .font(.subheadline)
                        .foregroundColor(Brand.textMuted)
                        .listRowBackground(Mint.panel(dark))
                }
                ForEach(model.history, id: \.id) { summary in
                    Button { model.openSplit(summary.id) } label: { row(summary, dark) }
                        .buttonStyle(.plain)
                        .listRowBackground(Mint.panel(dark))
                        .accessibilityAction(named: L.t(Strings.shared.split_delete)) { model.confirmingDeleteId = summary.id }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) { model.confirmingDeleteId = summary.id } label: {
                                Label(L.t(Strings.shared.split_delete), systemImage: "trash")
                            }
                        }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .frame(maxWidth: 600)
            .frame(maxWidth: .infinity)
            .refreshable { model.loadHistory() }
        }
        .alert(L.t(Strings.shared.split_delete), isPresented: deleteShown) {
            Button(L.t(Strings.shared.split_delete), role: .destructive) { model.delete() }
            Button(L.t(Strings.shared.action_cancel), role: .cancel) { model.confirmingDeleteId = nil }
        } message: {
            Text(L.t(Strings.shared.split_delete_confirm))
        }
    }

    private var deleteShown: Binding<Bool> {
        Binding(get: { model.confirmingDeleteId != nil }, set: { if !$0 { model.confirmingDeleteId = nil } })
    }

    private func row(_ summary: SplitSummary, _ dark: Bool) -> some View {
        let date = model.dateLabel(summary.occurredOn)
        let total = model.money(summary.total)
        let line = model.summaryLine(summary)
        let people = L.t(Strings.shared.split_people_count, String(summary.people))
        return HStack(alignment: .top, spacing: 12) {
            IconTile(
                symbol: summary.settled ? "checkmark.circle.fill" : "person.2.fill",
                accent: summary.settled ? Mint.greenText(dark) : Mint.orange,
                size: 40, iconSize: 18
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(summary.title).font(.body.weight(.medium)).foregroundColor(.primary).lineLimit(1).truncationMode(.tail)
                HStack(spacing: 8) {
                    Text(date)
                    Text(people)
                }
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
                .lineLimit(1)
                Text(line)
                    .font(.footnote.weight(.medium))
                    .foregroundColor(summary.settled ? Mint.greenText(dark) : Brand.amber)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(total).font(.headline).monospacedDigit().foregroundColor(.primary).lineLimit(1)
        }
        .tappableRow(minHeight: 56)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([summary.title, date, people, total, line].joined(separator: ", "))
    }
}
