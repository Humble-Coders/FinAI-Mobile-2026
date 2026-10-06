import SwiftUI
import SharedLogic

/**
 The household's savings goals: what each needs a month and whether it is on
 pace (#52, PRD F5). The Android screen is the same shape.

 In Home's language — the field with the budget comparison, a white sheet with
 the goals — because no design was supplied. Where a goal stands is always said
 in words; a long-term goal says it counts no investment growth, beside the
 region's disclaimer.
 */
struct GoalsView: View {

    @ObservedObject var model: GoalsViewModel
    let userId: String

    /// The edit in progress as the system keeps it for this scene, so the app
    /// coming back after iOS reclaimed it reopens what was being typed.
    @SceneStorage("goals.draft") private var stored = ""
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

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    if model.loading && model.page == nil {
                        // The coin covers a first load; an empty sheet under it
                        // would read as having no goals.
                        EmptyView()
                    } else if model.loadFailed {
                        loadFailed(dark)
                    } else {
                        sheet(dark)
                    }
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .onAppear {
            if !stored.isEmpty { model.restore(from: stored) }
            model.bind(userId: userId)
        }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        .sheet(isPresented: editorShown) { GoalEditorSheet(model: model) }
        .sheet(isPresented: addShown) { AddToGoalSheet(model: model) }
    }

    private var editorShown: Binding<Bool> {
        Binding(get: { model.editorOpen }, set: { if !$0 { model.cancelEdit() } })
    }

    private var addShown: Binding<Bool> {
        Binding(get: { model.addingTo != nil }, set: { if !$0 { model.cancelAdd() } })
    }

    // MARK: - In the field

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(L.t(Strings.shared.goals_title))
                    .font(.largeTitle.weight(.bold))
                    .foregroundColor(Field.ink())
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if model.goals.count > 1 {
                    Button(L.t(model.reordering ? Strings.shared.action_done : Strings.shared.goals_reorder_hint)) {
                        model.reordering.toggle()
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Field.ink())
                }
            }
            if let line = model.comparisonLine {
                HStack(spacing: 10) {
                    // A shortfall is said in words; the mark only draws the eye.
                    if model.hasShortfall {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundColor(Field.ink(0.9))
                    }
                    Text(line).font(.subheadline).foregroundColor(Field.ink())
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14).fill(Field.glass))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Field.glassEdge, lineWidth: 1))
            }
        }
    }

    // MARK: - The sheet

    private func sheet(_ dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.showsEmpty {
                Text(L.t(Strings.shared.goals_empty_title)).font(.headline)
                Text(L.t(Strings.shared.goals_empty_body)).font(.subheadline).foregroundColor(.secondary)
            }

            ForEach(Array(model.goals.enumerated()), id: \.element.id) { index, goal in
                GoalRow(model: model, goal: goal, index: index, dark: dark)
            }

            Button { model.startNew() } label: {
                HStack(spacing: 12) {
                    IconTile(symbol: "plus", accent: Mint.greenText(dark), size: 40, iconSize: 18)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L.t(Strings.shared.goals_new)).foregroundColor(.primary)
                        // At the limit, said before the editor opens.
                        if model.atLimit {
                            Text(L.t(Strings.shared.goals_block_too_many)).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 44)
            }
            .buttonStyle(.plain)

            // A refusal that is not about one sheet — a reorder that failed, say.
            if let notice = model.noticeKey {
                Text(L.t(notice)).font(.caption).foregroundColor(.red)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 24).fill(Mint.panel(dark)))
    }

    private func loadFailed(_ dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L.t(model.errorKey ?? Strings.shared.error_title)).font(.subheadline)
            Button(L.t(Strings.shared.action_retry)) { model.retry() }.font(.subheadline.weight(.semibold))
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Mint.card(dark)))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Mint.edge(dark), lineWidth: 1))
    }
}

// MARK: - One goal

private struct GoalRow: View {

    @ObservedObject var model: GoalsViewModel
    let goal: Goal
    let index: Int
    let dark: Bool

    var body: some View {
        let icon = GoalEdit.shared.iconOf(kind: goal.kind)
        HStack(alignment: .top, spacing: 12) {
            Button { model.edit(goalId: goal.id) } label: {
                HStack(alignment: .top, spacing: 12) {
                    IconTile(symbol: icon.systemName, accent: icon.tint(dark), size: 40, iconSize: 18)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(goal.name)
                            .font(.body.weight(.medium))
                            .foregroundColor(.primary)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Text(model.amounts(of: goal)).font(.caption).foregroundColor(.secondary)
                        ProgressView(value: Double(goal.fraction)).tint(Mint.greenText(dark))
                        let status = model.status(of: goal)
                        if !status.isEmpty { Text(status).font(.caption.weight(.medium)).foregroundColor(.primary) }
                        if let growth = model.growthLine(of: goal) { Text(growth).font(.caption2).foregroundColor(.secondary) }
                        if let disclaimer = model.disclaimer(of: goal) { Text(disclaimer).font(.caption2).foregroundColor(.secondary) }
                    }
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(.plain)

            if model.reordering {
                // The same native buttons as Android: the screen is a field and
                // a sheet, not a List, so there is no `.onMove` to use — and
                // these are what VoiceOver needs anyway.
                VStack(spacing: 0) {
                    Button { model.moveUp(index) } label: { Image(systemName: "chevron.up").tappableArea() }
                        .disabled(!model.canMoveUp(index))
                        .accessibilityLabel(L.t(Strings.shared.goals_move_up))
                    Button { model.moveDown(index) } label: { Image(systemName: "chevron.down").tappableArea() }
                        .disabled(!model.canMoveDown(index))
                        .accessibilityLabel(L.t(Strings.shared.goals_move_down))
                }
            } else if !goal.isAchieved {
                Button(L.t(Strings.shared.goals_add_money)) { model.startAdd(goalId: goal.id) }
                    .font(.caption.weight(.semibold))
                    .frame(minHeight: 44)
            }
        }
        .padding(.vertical, 4)
        // One sentence, so the goal is heard once and its standing in words.
        // "Add money" and the moves are actions on it, not more stops.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.description(of: goal))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { model.edit(goalId: goal.id) }
        .accessibilityAction(named: L.t(Strings.shared.goals_add_money)) {
            if !goal.isAchieved { model.startAdd(goalId: goal.id) }
        }
        .accessibilityAction(named: L.t(Strings.shared.goals_move_up)) {
            if model.canMoveUp(index) { model.moveUp(index) }
        }
        .accessibilityAction(named: L.t(Strings.shared.goals_move_down)) {
            if model.canMoveDown(index) { model.moveDown(index) }
        }
    }
}
