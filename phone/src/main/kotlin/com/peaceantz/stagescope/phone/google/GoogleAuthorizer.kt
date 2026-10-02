package com.peaceantz.stagescope.phone.google

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.ai.core.get
import com.peaceantz.stagescope.phone.ai.core.parseObject
import com.peaceantz.stagescope.phone.ai.core.str
import kotlinx.coroutines.tasks.await
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Who a token belongs to -- so a confirmation can be bound to, and checked against, one account. */
data class GoogleIdentity(val accountId: String, val email: String)

sealed interface TokenResult {
    data class Token(val accessToken: String, val identity: GoogleIdentity?) : TokenResult

    /** The person has not granted (or has revoked) these scopes; show consent from an Activity. */
    data class NeedsConsent(val pendingIntent: PendingIntent?) : TokenResult

    data class Unavailable(val reason: String) : TokenResult
}

/**
 * Google user-data authorization, kept separate from AI credentials: the AI never sees a token, and a
 * token is never written to disk by StageScope (the Authorization API caches short-lived tokens
 * itself and re-issues them silently for as long as the grant stands).
 */
interface GoogleAuthorizer {
    /** Silent attempt: returns a token only if consent already exists. Never shows UI. */
    suspend fun accessToken(scopes: List<String>): TokenResult
    suspend fun revoke(scopes: List<String>)
}

class AndroidGoogleAuthorizer(
    private val context: Context,
    private val http: ProviderHttp,
) : GoogleAuthorizer {

    override suspend fun accessToken(scopes: List<String>): TokenResult {
        val result = try {
            Identity.getAuthorizationClient(context).authorize(request(scopes)).await()
        } catch (e: Exception) {
            return TokenResult.Unavailable("Google sign-in isn't available: ${e.message?.take(120) ?: e.javaClass.simpleName}")
        }
        if (result.hasResolution()) return TokenResult.NeedsConsent(result.pendingIntent)
        val token = result.accessToken ?: return TokenResult.Unavailable("Google returned no access token.")
        return TokenResult.Token(token, identityOf(token))
    }

    override suspend fun revoke(scopes: List<String>) {
        runCatching {
            Identity.getAuthorizationClient(context)
                .revokeAccess(com.google.android.gms.auth.api.identity.RevokeAccessRequest.builder().setScopes(scopes.map { Scope(it) }).build()).await()
        }
    }

    /** The authorization response carries no identity, so ask Google who the token is for (openid+email). */
    private suspend fun identityOf(token: String): GoogleIdentity? {
        val r = runCatching {
            http.execute(http.get("https://www.googleapis.com/oauth2/v3/userinfo".toHttpUrl(), mapOf("Authorization" to "Bearer $token")))
        }.getOrNull() ?: return null
        if (r.status != 200) return null
        val json = parseObject(r.body) ?: return null
        val sub = json["sub"].str() ?: return null
        val email = json["email"].str() ?: return null
        return GoogleIdentity(sub, email)
    }

    private fun request(scopes: List<String>): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) }).build()
}
