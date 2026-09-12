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
        guard let presenter = topViewController() else {
            model.onProviderFailed(Strings.shared.error_provider_failed)
            return
        }

        GIDSignIn.sharedInstance.configuration =
            GIDConfiguration(clientID: GoogleConfig.shared.IOS_CLIENT_ID)
        model.onProviderStarted()

        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
            if let error {
                // A dismissed sheet is a choice, not a failure.
                if (error as NSError).code == GIDSignInError.canceled.rawValue {
                    model.onProviderCancelled()
                } else {
                    model.onProviderFailed(Strings.shared.error_provider_failed)
                }
                return
            }
            guard let idToken = result?.user.idToken?.tokenString else {
                model.onProviderFailed(Strings.shared.error_provider_failed)
                return
            }
            // No nonce: Google's iOS SDK does not expose one, which is why the
            // Supabase provider needs "skip nonce check" enabled for it.
            model.signInWithProvider(.google, idToken: idToken, nonce: nil)
        }
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
