import AuthenticationServices
import CryptoKit
import Foundation
import SharedLogic
import SwiftUI

/// Sign in with Apple, driven from our own button.
///
/// Apple's `SignInWithAppleButton` always carries a label. The design shows the
/// providers as equal circles, which Apple's branding rules allow when every
/// provider is shown that way, at the same size and with their logo — so the
/// button is ours (`ProviderCircleButton`) and this drives the request behind it.
///
/// The nonce is the part worth care: Apple is given the SHA-256 **hash**, while
/// Supabase is given the **raw** value and hashes it again to compare. Sending
/// the same form to both fails the check.
@MainActor
final class AppleSignIn: NSObject, ASAuthorizationControllerDelegate,
                         ASAuthorizationControllerPresentationContextProviding {

    /// Kept alive for the length of the request: `ASAuthorizationController`
    /// holds its delegate weakly, and a released one silently never answers.
    private static var inFlight: AppleSignIn?

    private let model: OnboardingViewModel
    private let rawNonce: String

    private init(model: OnboardingViewModel) {
        self.model = model
        self.rawNonce = Self.randomNonce()
    }

    static func start(model: OnboardingViewModel) {
        let flow = AppleSignIn(model: model)
        inFlight = flow

        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = Self.sha256(flow.rawNonce)

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = flow
        controller.presentationContextProvider = flow
        model.onProviderStarted()
        controller.performRequests()
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?
            .windows.first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        defer { Self.inFlight = nil }
        guard
            let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
            let tokenData = credential.identityToken,
            let idToken = String(data: tokenData, encoding: .utf8)
        else {
            model.onProviderFailed(Strings.shared.error_provider_failed)
            return
        }
        // Apple returns name and email ONLY on the first authorization (PRD F1).
        // The name is deliberately not captured at signup (2026-09-15).
        model.signInWithProvider(.apple, idToken: idToken, nonce: rawNonce)
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        defer { Self.inFlight = nil }
        // A dismissed sheet is a choice, not a failure.
        if (error as? ASAuthorizationError)?.code == .canceled {
            model.onProviderCancelled()
        } else {
            model.onProviderFailed(Strings.shared.error_provider_failed)
        }
    }

    /// A cryptographically random nonce, so a replayed ID token is useless.
    private static func randomNonce(length: Int = 32) -> String {
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-._")
        var bytes = [UInt8](repeating: 0, count: length)
        let status = SecRandomCopyBytes(kSecRandomDefault, length, &bytes)
        guard status == errSecSuccess else {
            // A predictable nonce defeats the point of having one, so this is
            // not something to paper over with a weaker source.
            fatalError("SecRandomCopyBytes failed: \(status)")
        }
        return String(bytes.map { charset[Int($0) % charset.count] })
    }

    private static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
