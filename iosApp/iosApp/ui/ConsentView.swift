import SwiftUI
import SharedLogic

/// The account terms, and the region the server derived.
///
/// The region row is here because the PRD requires the override to be reachable
/// *during* onboarding: a misdetected Canadian fixes it on the spot rather than
/// being locked out. What it shows is always the API's answer from the verified
/// number — never the dialling code picked earlier.
struct ConsentView: View {
    @ObservedObject var model: OnboardingViewModel
    let onChangeRegion: () -> Void

    var body: some View {
        ScreenScaffold {
            Text(L.t(Strings.shared.consent_title)).font(.title2.weight(.semibold))
            Text(L.t(Strings.shared.consent_subtitle))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)

            // Laid out in full, with no scroll of its own - ScreenScaffold's is
            // the only one. Long terms push the button below the fold, which for
            // a consent screen is the right way round anyway.
            Group {
                if let terms = model.terms {
                    Text(terms.body)
                        .font(.footnote)
                        .foregroundColor(Brand.textMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                }
            }
            .padding(16)
            .background(Brand.surface)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            if let region = model.me?.household.countryCode {
                Button(action: onChangeRegion) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L.t(Strings.shared.consent_region_label))
                                .font(.caption)
                                .foregroundColor(Brand.textMuted)
                            Text(countryName(region))
                                .font(.body)
                                .foregroundColor(.primary)
                        }
                        Spacer()
                        Text(L.t(Strings.shared.action_change))
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(Brand.green)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(Brand.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
            }

            ErrorText(messageKey: model.errorKey)

            PrimaryButton(
                title: L.t(Strings.shared.consent_agree),
                enabled: model.terms != nil,
                busy: model.busy
            ) { model.acceptTerms() }
        }
    }
}
