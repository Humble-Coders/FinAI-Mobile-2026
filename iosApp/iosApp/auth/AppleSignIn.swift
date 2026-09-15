import AuthenticationServices
import CryptoKit
import Foundation
import SharedLogic
import SwiftUI

/// Sign in with Apple, which is **mandatory on iOS** wherever another
/// third-party provider is offered (App Store guideline 4.8).
///
/// The nonce is the part worth care: Apple is given the SHA-256 **hash**, while
/// Supabase is given the **raw** value and hashes it again to compare. Sending
/// the same form to both fails the check.
struct AppleSignInButton: View {
    @ObservedObject var model: OnboardingViewModel
    @State private var rawNonce = ""

    var body: some View {
        SignInWithAppleButton(.continue) { request in
            let nonce = Self.randomNonce()
            rawNonce = nonce
            request.requestedScopes = [.fullName, .email]
            request.nonce = Self.sha256(nonce)
            model.onProviderStarted()
        } onCompletion: { result in
            switch result {
            case let .success(authorization):
                guard
                    let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                    let tokenData = credential.identityToken,
                    let idToken = String(data: tokenData, encoding: .utf8)
                else {
                    model.onProviderFailed(Strings.shared.error_provider_failed)
                    return
                }
                // Apple returns name and email ONLY on the first authorization
                // (PRD F1). The name is deliberately not captured at signup
                // (manager decision, 2026-09-15). The model decides whether this
                // token signs in or links to the account on the link screen.
                model.signInWithProvider(.apple, idToken: idToken, nonce: rawNonce)

            case let .failure(error):
                if (error as? ASAuthorizationError)?.code == .canceled {
                    model.onProviderCancelled()
                } else {
                    model.onProviderFailed(Strings.shared.error_provider_failed)
                }
            }
        }
        // Apple requires their own button for this flow, so it cannot be a
        // ProviderButton. whiteOutline is the closest permitted style to the
        // outlined buttons beside it.
        .signInWithAppleButtonStyle(.whiteOutline)
        .frame(maxWidth: .infinity, minHeight: 52)
        .clipShape(RoundedRectangle(cornerRadius: 10))
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
