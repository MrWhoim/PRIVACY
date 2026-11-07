package com.privacy.app.credits

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** The phone's own Google account sheet – no browser. Returns a Google ID token that Supabase turns into a login. */
object NativeGoogle {
    class Cancelled : Exception("Sign-in cancelled.")

    suspend fun idToken(activity: Activity): String {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(CreditsConfig.GOOGLE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        try {
            val c = CredentialManager.create(activity).getCredential(activity, request).credential
            if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                return GoogleIdTokenCredential.createFrom(c.data).idToken
            throw IllegalStateException("Unexpected sign-in result.")
        } catch (e: GetCredentialCancellationException) {
            throw Cancelled()
        } catch (e: GetCredentialException) {
            throw IllegalStateException(e.message ?: "Google sign-in failed.")
        }
    }
}
