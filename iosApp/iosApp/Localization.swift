import Foundation
import SharedLogic

/// Resolves a shared string key.
///
/// Swift never holds a user-facing literal — every label comes from
/// sharedLogic/i18n so Android and iOS cannot diverge (kmp-arch-v2, CLAUDE.md).
enum L {

    /// Kotlin default arguments do not cross to Swift (SKIE), so the language
    /// is passed explicitly every time.
    private static let language = "en"

    static func t(_ key: String) -> String {
        LocalizationRegistry.shared.get(key: key, language: language)
    }

    /// The same, filling `{0}`-style placeholders. Substitution lives in shared
    /// code so both platforms render one template identically.
    static func t(_ key: String, _ args: String...) -> String {
        LocalizationRegistry.shared.format(key: key, args: args, language: language)
    }
}

/// A country's name in the user's own language.
///
/// From `Locale` rather than the shared string table: it already translates
/// every region code, so hand-written English names would be wrong for most
/// users. Shared owns the dialling codes; the platform owns the words.
func countryName(_ region: String) -> String {
    Locale.current.localizedString(forRegionCode: region) ?? region
}
