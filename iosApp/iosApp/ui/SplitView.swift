import PhotosUI
import SharedLogic
import SwiftUI
import UIKit

/**
 Split expense: the bill, the people, who had what, and the split to send.
 Android's `SplitScreen`.

 One step at a time, back going to the one before (`SplitViewModel.steps`).
 The start sits on Home's green field; the steps after it on the mint field
 the import uses. Every rule and word is shared `SplitExpense`'s; the waits
 that earn it — reading a receipt — are the app's coin (RootView).
 */
struct SplitView: View {
    @ObservedObject var model: SplitViewModel
    let userId: String
    let onClose: () -> Void

    @State private var pickingFile = false
    @State private var photo: PhotosPickerItem?
    @State private var takingPhoto = false

    var body: some View {
        // A container, not a Group: a Group hands its modifiers to each step,
        // so every change of step would disappear one and unbind the clients.
        ZStack {
            switch model.step {
            case .start: SplitStartView(model: model, onBack: back)
            case .bill:
                SplitBillStep(
                    model: model,
                    photo: $photo,
                    onPickFile: { pickingFile = true },
                    onTakePhoto: { takingPhoto = true },
                    onBack: back
                )
            case .people: SplitPeopleStep(model: model, onBack: back)
            case .assign: SplitAssignStep(model: model, onBack: back)
            case .review: SplitReviewStep(model: model, onBack: back, onDone: close)
            case .history: SplitHistoryStep(model: model, onBack: back)
            }
        }
        .onAppear { model.bind(userId: userId) }
        // Not while the camera is up: its full-screen cover makes this view
        // disappear, and the photo it comes back with needs the clients.
        .onDisappear { if !takingPhoto { model.unbind() } }
        .sheet(isPresented: consentShown) {
            ScrollView {
                AiConsentPanel(
                    title: L.t(Strings.shared.split_consent_title),
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
        .fileImporter(isPresented: $pickingFile, allowedContentTypes: [.pdf, .image]) { result in
            if case .success(let url) = result, let copy = try? ReceiptFiles.adopt(url) {
                model.receiptPicked(copy)
            }
        }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            photo = nil
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.9),
                   let file = try? ReceiptFiles.write(jpeg) {
                    model.receiptPicked(file)
                }
            }
        }
        .fullScreenCover(isPresented: $takingPhoto) {
            CameraPicker(
                onTaken: { data in
                    takingPhoto = false
                    if let file = try? ReceiptFiles.write(data) { model.receiptPicked(file) }
                },
                onCancel: { takingPhoto = false }
            )
            .ignoresSafeArea()
        }
    }

    private var consentShown: Binding<Bool> {
        Binding(get: { model.consentAsked }, set: { if !$0 { model.declineConsent() } })
    }

    private func back() {
        dismissKeyboard()
        if !model.back() { close() }
    }

    private func close() {
        model.close()
        onClose()
    }
}

// MARK: - The frame every step after the start shares

/// The mint field, the header with back and the step dashes, the step itself,
/// and its bar of actions kept in reach at the bottom.
struct SplitStepFrame<Content: View, Bar: View>: View {
    @ObservedObject var model: SplitViewModel
    let decoration: String
    /// A list draws its own rows under the header, so it is solid from the start.
    var list = false
    /// Off while the step has nothing to press at the bottom yet.
    var showsBar = true
    let onBack: () -> Void
    @ViewBuilder let content: () -> Content
    @ViewBuilder let bar: () -> Bar

    @State private var scrolledUnder = false

    var body: some View {
        ZStack {
            MintBackdrop(decoration: decoration)
            if list {
                content()
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) { content() }
                        .modifier(ScrolledUnder(scrolled: $scrolledUnder))
                        .frame(maxWidth: 560)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 16)
                }
                .scrollBounceBehavior(.basedOnSize)
                .scrollDismissesKeyboard(.interactively)
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            MintHeader(
                title: L.t(Strings.shared.split_title),
                step: model.stepNumber,
                steps: model.stepCount,
                solid: list || scrolledUnder,
                onBack: onBack
            )
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if showsBar && Bar.self != EmptyView.self {
                VStack(spacing: 8) { bar() }
                    .frame(maxWidth: 560)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity)
                    .background(.bar)
            }
        }
    }
}

extension SplitStepFrame where Bar == EmptyView {
    init(model: SplitViewModel, decoration: String, list: Bool = false, onBack: @escaping () -> Void, @ViewBuilder content: @escaping () -> Content) {
        self.init(model: model, decoration: decoration, list: list, onBack: onBack, content: content) { EmptyView() }
    }
}

/// A refusal or a reason, above the button it holds back.
struct SplitNotice: View {
    let text: String?

    var body: some View {
        if let text {
            Text(text)
                .font(.footnote)
                .foregroundColor(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Monograms

/// A person as their initials in a tinted round — never a photo.
struct Monogram: View {
    let initials: String
    var size: CGFloat = 44
    var tint: Color = Brand.green
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(initials)
            .font(.system(size: size * 0.36, weight: .semibold))
            .foregroundColor(.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .frame(width: size, height: size)
            .background(Circle().fill(tint.opacity(scheme == .dark ? 0.32 : 0.22)))
            .accessibilityHidden(true)
    }

    /// The person themselves in green; everyone else in turn, so neighbours differ.
    static func tint(_ index: Int) -> Color {
        index == 0 ? Brand.green : Mint.accountTints[index % Mint.accountTints.count]
    }
}

/// Chips laid out in rows, wrapping as the width runs out.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        return arrange(subviews, width: width).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let placed = arrange(subviews, width: bounds.width)
        for (index, origin) in placed.origins.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y),
                proposal: ProposedViewSize(width: min(placed.sizes[index].width, bounds.width), height: nil)
            )
        }
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> (size: CGSize, origins: [CGPoint], sizes: [CGSize]) {
        var origins: [CGPoint] = []
        var sizes: [CGSize] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var row: CGFloat = 0
        var widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(ProposedViewSize(width: width.isFinite ? width : nil, height: nil))
            if x > 0, x + size.width > width {
                x = 0
                y += row + spacing
                row = 0
            }
            origins.append(CGPoint(x: x, y: y))
            sizes.append(size)
            x += size.width + spacing
            row = max(row, size.height)
            widest = max(widest, x - spacing)
        }
        return (CGSize(width: widest, height: y + row), origins, sizes)
    }
}

// MARK: - 1. The start

/// Where the figures come from, how it is split, and who with — nothing chosen
/// for the person. On Home's green field, like the other tabs' screens.
struct SplitStartView: View {
    @ObservedObject var model: SplitViewModel
    let onBack: () -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var collapse = HeaderCollapse()

    private static let space = "split"

    var body: some View {
        let dark = scheme == .dark
        ZStack(alignment: .top) {
            ZStack {
                Field.gradient(dark)
                Waves()
                FieldVectors()
            }
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    sheet(dark)
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 24)
            }
            .coordinateSpace(name: Self.space)
            .scrollBounceBehavior(.basedOnSize)
            .overlay(alignment: .top) {
                CollapsingBar(collapse: collapse, dark: dark) {
                    ZStack {
                        CompactTitle(title: L.t(Strings.shared.split_title))
                        HStack {
                            backButton
                            Spacer()
                        }
                        .padding(.horizontal, 8)
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 8) {
                GradientButton(title: L.t(Strings.shared.split_continue), enabled: model.canContinueFromStart) {
                    model.continueFromStart()
                }
                Text(L.t(Strings.shared.split_edit_later))
                    .font(.footnote)
                    .foregroundColor(Brand.textMuted)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(.bar)
        }
    }

    private var backButton: some View {
        Button(action: onBack) {
            Image(systemName: "chevron.left")
                .font(.body.weight(.semibold))
                .foregroundColor(Field.ink())
                .tappableArea()
        }
        .accessibilityLabel(L.t(Strings.shared.action_back))
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                backButton.padding(.leading, -12)
                Spacer()
                Button { model.openHistory() } label: {
                    Label(L.t(Strings.shared.split_history), systemImage: "clock.arrow.circlepath")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Field.ink())
                        .lineLimit(1)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .frame(minHeight: 44)
                        .background(Capsule().fill(Field.glass))
                        .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
            Text(L.t(Strings.shared.split_title))
                .font(.largeTitle.weight(.bold))
                .foregroundColor(Field.ink())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .accessibilityAddTraits(.isHeader)
                .modifier(HeaderFade(collapse: collapse))
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.frame(in: .named(Self.space)).minY
                } action: { (top: CGFloat) in
                    collapse.track(top: top)
                }
            Text(L.t(Strings.shared.split_subtitle))
                .font(.subheadline)
                .foregroundColor(Field.ink(0.85))
        }
    }

    private func sheet(_ dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            choice(
                symbol: "doc.text.viewfinder", accent: Brand.green,
                title: Strings.shared.split_upload_receipt, detail: Strings.shared.split_upload_receipt_detail,
                selected: model.source == .receipt
            ) { model.chooseSource(.receipt) }
            choice(
                symbol: "square.and.pencil", accent: Brand.blue,
                title: Strings.shared.split_add_manually, detail: Strings.shared.split_add_manually_detail,
                selected: model.source == .manual
            ) { model.chooseSource(.manual) }

            section(Strings.shared.split_how)
            choice(
                symbol: "divide.circle.fill", accent: Mint.orange,
                title: Strings.shared.split_equally, detail: Strings.shared.split_equally_detail,
                selected: model.mode == .equal
            ) { model.chooseMode(.equal) }
            choice(
                symbol: "list.bullet.rectangle.fill", accent: Brand.purple,
                title: Strings.shared.split_by_items, detail: Strings.shared.split_by_items_detail,
                selected: model.mode == .items
            ) { model.chooseMode(.items) }

            section(Strings.shared.split_between)
            between
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Mint.panel(dark)))
    }

    private func section(_ key: String) -> some View {
        Text(L.t(key))
            .font(.headline)
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
    }

    private func choice(
        symbol: String, accent: Color, title: String, detail: String, selected: Bool, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            ChoiceCardLabel(symbol: symbol, accent: accent, title: L.t(title), detail: L.t(detail), selected: selected)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// You and everyone chosen, then the way to add more.
    private var between: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 14) {
                ForEach(Array(model.people.enumerated()), id: \.offset) { index, person in
                    let remove = L.t(Strings.shared.split_remove_person, model.label(person))
                    VStack(spacing: 6) {
                        Monogram(initials: model.initials(person), size: 52, tint: Monogram.tint(index))
                            .overlay(alignment: .topTrailing) {
                                // The person themselves is on every bill; anyone else can come off it.
                                if !person.isSelf {
                                    Button { model.remove(personAt: index) } label: {
                                        Image(systemName: "xmark")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundColor(.secondary)
                                            .frame(width: 22, height: 22)
                                            .background(Circle().fill(Color(.secondarySystemBackground)))
                                            .overlay(Circle().stroke(Color(.separator), lineWidth: 1))
                                            // A small mark, a full-size target.
                                            .frame(width: 44, height: 44)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .offset(x: 14, y: -14)
                                    .accessibilityHidden(true)
                                }
                            }
                        Text(model.label(person))
                            .font(.caption)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                    .frame(width: 64)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(model.label(person))
                    .accessibilityAction(named: remove) {
                        if !person.isSelf { model.remove(personAt: index) }
                    }
                }
                Button { model.openPeople() } label: {
                    VStack(spacing: 6) {
                        ZStack {
                            Circle()
                                .strokeBorder(Brand.green, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                            Image(systemName: "plus").font(.title3.weight(.semibold)).foregroundColor(Brand.green)
                        }
                        .frame(width: 52, height: 52)
                        Text(L.t(Strings.shared.split_add_people))
                            .font(.caption)
                            .foregroundColor(.primary)
                            .lineLimit(1)
                    }
                    .frame(minWidth: 64)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.vertical, 4)
        }
    }
}

// MARK: - 2. The bill

/// A receipt to read, or the bill typed in: what it was for, the date, and
/// the total — or each item, with tax and tip, when split by items.
struct SplitBillStep: View {
    @ObservedObject var model: SplitViewModel
    @Binding var photo: PhotosPickerItem?
    let onPickFile: () -> Void
    let onTakePhoto: () -> Void
    let onBack: () -> Void

    @State private var choosingDate = false

    var body: some View {
        SplitStepFrame(
            model: model,
            decoration: model.source == .receipt ? "doc.text.viewfinder" : "square.and.pencil",
            showsBar: showsForm,
            onBack: onBack
        ) {
            if model.source == .receipt {
                switch model.reading {
                case .choosing: choose
                // The coin covers the read; its caption says what is happening.
                case .reading: Color.clear.frame(height: 1)
                case .failed(let key, let retryable): failed(key, retryable: retryable)
                case .read: form(receipt: true)
                }
            } else {
                form(receipt: false)
            }
        } bar: {
            SplitNotice(text: model.billNotice)
            GradientButton(title: L.t(Strings.shared.split_continue), enabled: model.canContinueFromBill) {
                dismissKeyboard()
                model.continueFromBill()
            }
        }
        .sheet(isPresented: $choosingDate) {
            DateSheet(
                chosen: model.occurredDate,
                today: model.today,
                onChosen: { date in
                    model.chooseDate(date)
                    choosingDate = false
                },
                onCancel: { choosingDate = false }
            )
        }
    }

    private var showsForm: Bool { model.source == .manual || model.reading == .read }

    /// The three ways in, as the import offers them, and the way out to typing it.
    private var choose: some View {
        VStack(alignment: .leading, spacing: 20) {
            MintTitle(title: L.t(Strings.shared.split_upload_receipt), subtitle: L.t(Strings.shared.split_upload_receipt_detail))
            MintPanel {
                if CameraPicker.isAvailable {
                    Button(action: onTakePhoto) {
                        ChoiceCardLabel(
                            symbol: "camera.fill", accent: Mint.orange,
                            title: L.t(Strings.shared.import_take_photo),
                            tinted: true
                        )
                    }
                    .buttonStyle(.plain)
                }
                PhotosPicker(selection: $photo, matching: .images) {
                    ChoiceCardLabel(
                        symbol: "photo.fill", accent: Brand.blue,
                        title: L.t(Strings.shared.import_pick_photo),
                        detail: L.t(Strings.shared.import_pick_photo_detail)
                    )
                }
                .buttonStyle(.plain)
                Button(action: onPickFile) {
                    ChoiceCardLabel(symbol: "doc.text.fill", accent: Brand.green, title: L.t(Strings.shared.import_pick_file_title))
                }
                .buttonStyle(.plain)
                Button { model.switchToManual() } label: {
                    ChoiceCardLabel(
                        symbol: "square.and.pencil", accent: Brand.purple,
                        title: L.t(Strings.shared.split_add_manually),
                        detail: L.t(Strings.shared.split_add_manually_detail)
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// Why the receipt could not be read, and the ways on: again, another, or typing it.
    private func failed(_ key: String, retryable: Bool) -> some View {
        MintPanel {
            Text(L.t(Strings.shared.split_receipt_review))
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text(L.t(key))
                .font(.subheadline)
                .foregroundColor(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
            if retryable {
                GradientButton(title: L.t(Strings.shared.action_retry)) { model.retryReceipt() }
            }
            Button { model.retake() } label: {
                Text(L.t(Strings.shared.split_retake)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
            Button { model.switchToManual() } label: {
                Text(L.t(Strings.shared.split_add_manually)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
        }
    }

    private func form(receipt: Bool) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            MintTitle(
                title: L.t(receipt ? Strings.shared.split_receipt_review : Strings.shared.split_add_manually),
                subtitle: L.t(receipt ? Strings.shared.split_receipt_review_detail : Strings.shared.split_add_manually_detail)
            )
            MintPanel {
                WizardField(
                    label: L.t(Strings.shared.split_bill_name),
                    placeholder: L.t(Strings.shared.split_bill_name_hint),
                    autocapitalization: .sentences,
                    isError: model.billNotice != nil && model.billBlock == .noTitle,
                    submitLabel: .done,
                    text: $model.title
                )
                PickerField(
                    label: L.t(Strings.shared.split_bill_date),
                    value: model.dateLabel,
                    placeholder: L.t(Strings.shared.split_bill_date),
                    leading: "calendar",
                    trailing: "chevron.right"
                ) { choosingDate = true }

                if model.mode == .items {
                    items(receipt: receipt)
                } else {
                    AmountField(
                        label: L.t(Strings.shared.split_total),
                        symbol: model.symbol,
                        placeholder: model.amountPlaceholder,
                        isError: model.billNotice != nil && model.billBlock == .totalInvalid,
                        text: $model.total
                    )
                }

                if receipt && model.unreadable > 0 {
                    Text(L.t(Strings.shared.split_receipt_unreadable, String(model.unreadable)))
                        .font(.footnote)
                        .foregroundColor(Brand.amber)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if receipt {
                    Button { model.retake() } label: {
                        Label(L.t(Strings.shared.split_retake), systemImage: "arrow.counterclockwise")
                            .font(.subheadline.weight(.semibold))
                            .tappableRow()
                    }
                    .buttonStyle(.bordered)
                    .tint(.primary)
                }
            }
        }
    }

    @ViewBuilder
    private func items(receipt: Bool) -> some View {
        if receipt {
            FieldLabel(text: L.t(Strings.shared.split_items_title, String(model.items.count)))
                .accessibilityAddTraits(.isHeader)
        } else {
            Stepper(
                value: Binding(get: { model.items.count }, set: { model.setItemCount($0) }),
                in: 0...Int(SplitExpense.shared.MAX_ITEMS)
            ) {
                HStack {
                    Text(L.t(Strings.shared.split_item_count)).font(.subheadline.weight(.medium))
                    Spacer(minLength: 8)
                    Text(String(model.items.count)).font(.headline).monospacedDigit()
                }
            }
            .frame(minHeight: 44)
            .accessibilityValue(String(model.items.count))
        }
        ForEach(Array(model.items.indices), id: \.self) { index in
            SplitItemFields(
                label: L.t(Strings.shared.split_item_name, String(index + 1)),
                symbol: model.symbol,
                placeholder: model.amountPlaceholder,
                name: Binding(get: { model.itemName(index) }, set: { model.setItemName(index, $0) }),
                amount: Binding(get: { model.itemAmount(index) }, set: { model.setItemAmount(index, $0) }),
                onRemove: receipt ? { model.removeItem(index) } : nil
            )
        }
        if receipt && model.canAddItem {
            Button { model.addItem() } label: {
                Label(L.t(Strings.shared.split_add_item), systemImage: "plus")
                    .font(.subheadline.weight(.semibold))
                    .tappableRow()
            }
            .buttonStyle(.plain)
            .foregroundColor(Brand.greenDeep)
        }
        AmountField(
            label: L.t(Strings.shared.split_tax), symbol: model.symbol, placeholder: model.amountPlaceholder,
            isError: model.billNotice != nil && model.billBlock == .extraInvalid, large: false, text: $model.tax
        )
        AmountField(
            label: L.t(Strings.shared.split_tip), symbol: model.symbol, placeholder: model.amountPlaceholder,
            isError: model.billNotice != nil && model.billBlock == .extraInvalid, large: false, text: $model.tip
        )
    }
}

/// One line of the bill: its name and its price, side by side.
struct SplitItemFields: View {
    let label: String
    let symbol: String
    let placeholder: String
    @Binding var name: String
    @Binding var amount: String
    /// A receipt's lines can be wrong; a typed bill's count says how many.
    var onRemove: (() -> Void)? = nil

    private enum Focus { case name, amount }
    @FocusState private var focus: Focus?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FieldLabel(text: label)
            HStack(spacing: 8) {
                TextField("", text: $name, prompt: Text(label).foregroundColor(Brand.textMuted.opacity(0.6)))
                    .textInputAutocapitalization(.sentences)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .name)
                    .tint(Brand.green)
                    .lineLimit(1)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 52)
                    .fieldFrame(focused: focus == .name, isError: false)
                    .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
                    .onTapGesture { focus = .name }
                    .accessibilityLabel(label)
                HStack(spacing: 4) {
                    Text(symbol).foregroundColor(Brand.textMuted).accessibilityHidden(true)
                    TextField("", text: $amount, prompt: Text(placeholder).foregroundColor(Brand.textMuted.opacity(0.4)))
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .focused($focus, equals: .amount)
                        .tint(Brand.green)
                        .accessibilityLabel(L.t(Strings.shared.split_item_price))
                        .accessibilityHint(label)
                }
                .padding(.horizontal, 12)
                .frame(width: 132)
                .frame(minHeight: 52)
                .fieldFrame(focused: focus == .amount, isError: false)
                .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
                .onTapGesture { focus = .amount }
                if let onRemove {
                    Button(action: onRemove) {
                        Image(systemName: "trash")
                            .foregroundColor(Brand.textMuted)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L.t(Strings.shared.split_item_remove, name.isEmpty ? label : name))
                }
            }
        }
    }
}
