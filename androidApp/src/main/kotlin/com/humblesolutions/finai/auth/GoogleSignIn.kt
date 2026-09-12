package com.humblesolutions.finai.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.humblesolutions.finai.config.GoogleConfig

/** The user dismissed Google's sheet. A choice, not a failure. */
class GoogleSignInCancelled : Exception("cancelled")

/** Google's sheet could not produce an ID token. */
class GoogleSignInFailed(cause: Throwable?) : Exception("google sign-in failed", cause)

/** This build has no Google client id configured. */
class GoogleSignInNotConfigured : Exception("google client id missing")

/**
 * Google sign-in through Credential Manager.
 *
 * A native SDK, so it lives here rather than in shared code; only the ID token
 * crosses into the shared repository (kmp-arch-v2). Credential Manager is the
 * supported path — `GoogleSignInClient` is deprecated.
 *
 * The **web** client id is what goes here, not the Android one: Credential
 * Manager sends it as the `serverClientId`, and Supabase validates the token's
 * audience against it. The Android client id exists so Google can match the
 * app's signing certificate, and is never passed in code.
 */
object GoogleSignIn {

    suspend fun idToken(context: Context): String {
        if (!GoogleConfig.isConfigured) throw GoogleSignInNotConfigured()

        val option = GetSignInWithGoogleOption.Builder(GoogleConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw GoogleSignInCancelled()
        } catch (e: Exception) {
            throw GoogleSignInFailed(e)
        }

        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw GoogleSignInFailed(null)
    }
}
