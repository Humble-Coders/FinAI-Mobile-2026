import Foundation
import GoogleSignIn
import SharedLogic
import SwiftUI
import UIKit

/// Google sign-in on iOS, through Google's own SDK.
///
/// A native SDK, so it lives here rather than in shared code; the ID token it
/// returns goes through the same shared repository Apple's does (kmp-arch-v2).
enum GoogleSignInLauncher {

    /// Presents Google's sheet and hands the ID token to [model].
    @MainActor
    static func start(model: OnboardingViewModel) {
        guard GoogleConfig.shared.isConfigured else {
            model.onProviderFailed(Strings.shared.error_provider_not_configured)
            return
        }
        // Google's SDK raises an uncaught NSException - a crash, not an error -
        // when its URL scheme is absent from Info.plist. Refuse first.
        guard urlSchemeRegistered(for: GoogleConfig.shared.IOS_CLIENT_ID) else {
            model.onProviderFailed(Strings.shared.error_provider_not_configured)
            return
        }
        guard let presenter = topViewController() else {
            model.onProviderFailed(Strings.shared.error_provider_failed)
            return
        }

        GIDSignIn.sharedInstance.configuration =
            GIDConfiguration(clientID: GoogleConfig.shared.IOS_CLIENT_ID)
        model.onProviderStarted()

        // Google's completion handler is nonisolated, and every method on the
        // model is @MainActor. Calling straight across only compiles today
        // because the compiler downgrades it to a warning; under Swift 6 it is
        // an error, and in the meantime it is a main-actor method invoked from
        // no guaranteed actor at all. Hop explicitly.
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
            if let error {
                // A dismissed sheet is a choice, not a failure.
                let cancelled = (error as NSError).code == GIDSignInError.canceled.rawValue
                Task { @MainActor in
                    if cancelled {
                        model.onProviderCancelled()
                    } else {
                        model.onProviderFailed(Strings.shared.error_provider_failed)
                    }
                }
                return
            }
            guard let idToken = result?.user.idToken?.tokenString else {
                Task { @MainActor in
                    model.onProviderFailed(Strings.shared.error_provider_failed)
                }
                return
            }
            // No nonce: Google's iOS SDK does not expose one, which is why the
            // Supabase provider needs "skip nonce check" enabled for it.
            Task { @MainActor in
                model.signInWithProvider(.google, idToken: idToken, nonce: nil)
            }
        }
    }

    /// Whether `Info.plist` registers the scheme Google will try to return on.
    private static func urlSchemeRegistered(for clientId: String) -> Bool {
        guard let reversed = reversedClientId(for: clientId) else { return false }
        let types = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        return types.contains { entry in
            (entry["CFBundleURLSchemes"] as? [String])?.contains(reversed) == true
        }
    }

    /// `123-abc.apps.googleusercontent.com` -> `com.googleusercontent.apps.123-abc`.
    private static func reversedClientId(for clientId: String) -> String? {
        let suffix = ".apps.googleusercontent.com"
        guard clientId.hasSuffix(suffix) else { return nil }
        return "com.googleusercontent.apps." + String(clientId.dropLast(suffix.count))
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
