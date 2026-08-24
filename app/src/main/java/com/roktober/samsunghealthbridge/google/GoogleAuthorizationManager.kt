package com.roktober.samsunghealthbridge.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface GoogleAuthorizationState {
    data class Authorized(val accessToken: String) : GoogleAuthorizationState

    data class NeedsResolution(val pendingIntent: PendingIntent) : GoogleAuthorizationState
}

/**
 * Requests short-lived, client-side Google authorization for the one Drive file created by
 * this app. Tokens are intentionally kept in memory only; no refresh token or server auth code
 * is requested.
 */
class GoogleAuthorizationManager(
    context: Context,
    private val client: AuthorizationClient = Identity.getAuthorizationClient(context.applicationContext),
) {
    private val accessToken = AtomicReference<String?>(null)

    suspend fun authorize(): GoogleAuthorizationState {
        accessToken.get()?.let { return GoogleAuthorizationState.Authorized(it) }
        return client.authorize(AUTHORIZATION_REQUEST).await().toStateAndRemember()
    }

    /** Parses the successful result delivered by an Activity Result pending-intent launcher. */
    fun parseAuthorizationResult(data: Intent): GoogleAuthorizationState =
        client.getAuthorizationResultFromIntent(data).toStateAndRemember()

    /**
     * Clears a rejected token from both this process and Google Play services' local cache.
     * Call this after an API response with HTTP 401, then call [authorize] again.
     */
    suspend fun clearInvalidToken(token: String? = accessToken.get()) {
        accessToken.compareAndSet(token, null)
        if (!token.isNullOrBlank()) {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }
    }

    fun forgetInMemoryToken() {
        accessToken.set(null)
    }

    private fun AuthorizationResult.toStateAndRemember(): GoogleAuthorizationState {
        if (hasResolution()) {
            val resolution = requireNotNull(pendingIntent) {
                "Google authorization requires resolution but did not supply an intent"
            }
            return GoogleAuthorizationState.NeedsResolution(resolution)
        }

        val token = requireNotNull(accessToken?.takeIf(String::isNotBlank)) {
            "Google authorization completed without an access token"
        }
        this@GoogleAuthorizationManager.accessToken.set(token)
        return GoogleAuthorizationState.Authorized(token)
    }

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener { continuation.cancel() }
        }

    companion object {
        const val DRIVE_FILE_SCOPE: String = "https://www.googleapis.com/auth/drive.file"

        private val AUTHORIZATION_REQUEST: AuthorizationRequest =
            AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                .build()
    }
}
