import SwiftUI
import SharedLogic

/// Asked **only** when the API reports the `region` step — a number
/// libphonenumber could not place. The user picks; nothing is guessed.
struct RegionView: View {
    @ObservedObject var model: OnboardingViewModel
    let onPick: (String) -> Void

    @State private var query = ""

    private var regions: [String] {
        let all = DialCodes.shared.all.map(\.region)
            .sorted { countryName($0) < countryName($1) }
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return all }
        return all.filter { countryName($0).lowercased().contains(needle) }
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text(L.t(Strings.shared.region_body))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
                    .padding(.horizontal, 20)
                ErrorText(messageKey: model.errorKey).padding(.horizontal, 20)
                List(regions, id: \.self) { region in
                    Button { onPick(region) } label: {
                        Text(countryName(region)).foregroundColor(.primary)
                    }
                    .disabled(model.busy)
                }
                .listStyle(.plain)
            }
            .background(Brand.ground)
            .searchable(text: $query)
            .navigationTitle(L.t(Strings.shared.region_title))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
