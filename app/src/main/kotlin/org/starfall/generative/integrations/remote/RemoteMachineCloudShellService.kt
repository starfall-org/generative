package org.starfall.generative.integrations.remote

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.auth.api.identity.Identity
import java.net.ServerSocket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.starfall.generative.shared.subfeatures.auth.logic.AppAuthTransactions
import org.starfall.generative.shared.subfeatures.auth.logic.OAuthBrowser
import org.starfall.generative.shared.subfeatures.auth.logic.OAuthCallbackService
import org.starfall.generative.shared.subfeatures.auth.logic.awaitLoopbackAuthorizationCode
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.GoogleOAuthClientType
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineType
import org.starfall.generative.shared.model.RemoteOAuthToken
import org.starfall.generative.shared.model.remote.REMOTE_GOOGLE_CLOUD_SCOPE
import org.starfall.generative.shared.helpers.networking.ToolHttp



/** Handles Google Cloud Shell OAuth tokens, endpoint discovery and endpoint caching. */
internal class RemoteMachineCloudShellService(
    context: Context,
    private val store: RemoteMachineStore,
    private val assets: RemoteMachineAssetsService,
    private val authHttp: ToolHttp = ToolHttp()
) {
    private val appContext = context.applicationContext
    private val state = store.state
    private val json = Json { ignoreUnknownKeys = true }
    private val endpointCacheLock = Any()
    private val cloudEndpointCache = mutableMapOf<String, CachedCloudEndpoint>()

    private data class CachedCloudEndpoint(
        val host: String,
        val username: String,
        val lastUsedAtElapsedRealtime: Long
    )

    fun invalidateCache(machineId: String) {
        synchronized(endpointCacheLock) { cloudEndpointCache.remove(machineId) }
    }

    suspend fun authorizeGCloud(client: GCloudOAuthClient): Result<Unit> = result {
        require(client.clientId.isNotBlank()) { "Google OAuth client ID is missing." }
        // Google Desktop OAuth clients use the loopback redirect flow. Use a local port just like
        // the reference vscode-remote-services implementation instead of a custom app scheme.
        val callbackPort = ServerSocket(0).use { it.localPort }
        val redirectUri = "http://127.0.0.1:$callbackPort"
        val request = AppAuthTransactions.authorization(
            authorizationEndpoint = RemoteMachineService.GOOGLE_AUTHORIZATION_ENDPOINT,
            tokenEndpoint = RemoteMachineService.GOOGLE_TOKEN_ENDPOINT,
            clientId = client.clientId,
            redirectUri = redirectUri,
            scope = REMOTE_GOOGLE_CLOUD_SCOPE,
            additional = mapOf("access_type" to "offline", "prompt" to "consent")
        )
        val code = OAuthCallbackService.keepAlive(appContext) {
            awaitLoopbackAuthorizationCode(
                redirectUri = redirectUri,
                url = request.toUri().toString(),
                state = request.state ?: error("OAuth state is missing")
            ) { url ->
                OAuthBrowser.open(appContext, url)
            }
        }
        val payload = AppAuthTransactions.exchange(
            appContext,
            authHttp,
            AppAuthTransactions.token(
                RemoteMachineService.GOOGLE_TOKEN_ENDPOINT,
                client.clientId,
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to (request.codeVerifier ?: error("PKCE verifier is missing"))
                )
            ),
            AppAuthTransactions.authentication(
                if (client.clientSecret.isBlank()) "none" else "client_secret_post",
                client.clientSecret.takeIf(String::isNotBlank)
            )
        )
        val token = tokenFromPayload(payload, null)
        store.update { it.copy(oauthTokens = it.oauthTokens + (client.id to token)) }
    }

    /** Stores the short-lived access token issued by Android Google Identity authorization. */
    suspend fun storeAndroidOAuthToken(client: GCloudOAuthClient, accessToken: String) {
        require(client.type == GoogleOAuthClientType.ANDROID) { "The selected OAuth client is not an Android client." }
        require(accessToken.isNotBlank()) { "Google did not return an access token." }
        val token = RemoteOAuthToken(
            accessToken = accessToken,
            // AuthorizationClient returns a short-lived access token, not a refresh token.
            // accessToken() calls authorize() again at expiry; Google silently returns a fresh
            // token while the requested scope remains granted. Desktop clients use OAuth refresh tokens.
            expiresAt = System.currentTimeMillis() + RemoteMachineService.ANDROID_ACCESS_TOKEN_LIFETIME_MS
        )
        store.update { it.copy(oauthTokens = it.oauthTokens + (client.id to token)) }
    }

    /** Gets the current Cloud Shell endpoint and returns an updated machine record. */
    suspend fun refreshGCloudIp(
        machine: RemoteMachine,
        forceRefresh: Boolean = true
    ): Result<RemoteMachine> = result {
        require(machine.type == RemoteMachineType.GCLOUD_SHELL) { "Only Cloud Shell machines have a remote IP lookup." }
        if (!forceRefresh) cachedCloudEndpoint(machine)?.let { return@result it }
        val client = clientFor(machine)
        val token = accessToken(client)
        val api = CloudShellApi(token, json)
        val publicKey = assets.cloudShellPublicKey(machine)
        var environment = api.getEnvironment(machine.environmentName)
        val needsAuthorization = environment.isRunning() || environment.isPending()
        if (!environment.isRunning()) {
            if (environment.isPending()) {
                api.waitUntilRunning(machine.environmentName)
            } else {
                api.startEnvironment(machine.environmentName, publicKey?.let(::listOf).orEmpty())
            }
        }
        if (needsAuthorization) {
            // Keep an already-running environment's gcloud credentials current. A fresh start
            // receives the same token in its start request.
            api.authorizeEnvironment(machine.environmentName)
        }
        environment = api.getEnvironment(machine.environmentName)
        if (publicKey != null && !environment.hasPublicKey(publicKey)) {
            api.addPublicKey(machine.environmentName, publicKey)
            environment = api.getEnvironment(machine.environmentName)
        }
        val endpoint = environment.endpoint()
        val (host, _) = endpoint ?: error("Google Cloud Shell did not return an SSH endpoint.")
        val apiUsername = environment.firstText("sshUsername", "username", "user").orEmpty()
        // The Cloud Shell API is the source of truth for the SSH user; stored values are only a fallback.
        val username = apiUsername.ifBlank { machine.username.ifBlank { machine.gcloudUsername } }
        val updated = machine.copy(
            host = host,
            port = 6_000,
            username = username,
            gcloudUsername = username,
            passwordReference = remotePasswordReference(username, host, 6_000)
        )
        synchronized(endpointCacheLock) {
            cloudEndpointCache[machine.id] = CachedCloudEndpoint(
                host = host,
                username = username,
                lastUsedAtElapsedRealtime = SystemClock.elapsedRealtime()
            )
        }
        store.update { current ->
            val saved = current.machines.firstOrNull { it.id == machine.id }
                ?.takeIf { it.type == RemoteMachineType.GCLOUD_SHELL }
            if (saved == null) current else {
                val savedUsername = username.ifBlank { saved.username }
                val savedMachine = saved.copy(
                    host = host,
                    port = 6_000,
                    username = savedUsername,
                    gcloudUsername = savedUsername,
                    passwordReference = remotePasswordReference(savedUsername, host, 6_000),
                    password = updated.password,
                    identityPassphrase = updated.identityPassphrase
                )
                current.copy(
                    machines = current.machines.map { if (it.id == savedMachine.id) savedMachine else it }
                )
            }
        }
        updated
    }

    /**
     * Reads the Cloud Shell SSH user from the Google API without starting or modifying the
     * environment (a plain GET), so the editor can fill it in as soon as Google is authorized.
     */
    suspend fun fetchGCloudUsername(machine: RemoteMachine): Result<String> = result {
        require(machine.type == RemoteMachineType.GCLOUD_SHELL) { "Only Cloud Shell machines have an API user." }
        val client = clientFor(machine)
        val api = CloudShellApi(accessToken(client), json)
        api.getEnvironment(machine.environmentName)
            .firstText("sshUsername", "username", "user")
            ?: error("Google Cloud Shell did not return an SSH user.")
    }

    private fun cachedCloudEndpoint(machine: RemoteMachine): RemoteMachine? = synchronized(endpointCacheLock) {
        val cached = cloudEndpointCache[machine.id] ?: return@synchronized null
        val now = SystemClock.elapsedRealtime()
        if (now - cached.lastUsedAtElapsedRealtime >= RemoteMachineService.CLOUD_ENDPOINT_CACHE_TTL_MILLIS) {
            cloudEndpointCache.remove(machine.id)
            return@synchronized null
        }
        cloudEndpointCache[machine.id] = cached.copy(lastUsedAtElapsedRealtime = now)
        val username = cached.username.ifBlank { machine.username }
        machine.copy(
            host = cached.host,
            port = 6_000,
            username = username,
            gcloudUsername = username,
            passwordReference = remotePasswordReference(username, cached.host, 6_000)
        )
    }

    suspend fun startGCloudMachine(machine: RemoteMachine): Result<RemoteMachine> = result {
        require(machine.type == RemoteMachineType.GCLOUD_SHELL)
        refreshGCloudIp(machine).getOrThrow()
    }

    private suspend fun accessToken(client: GCloudOAuthClient): String {
        val existing = state.value.oauthTokens[client.id]
        if (existing != null && existing.accessToken.isNotBlank() &&
            (existing.expiresAt == null || existing.expiresAt > System.currentTimeMillis() + 60_000)
        ) return existing.accessToken

        // Android OAuth clients use Google Identity's authorization cache, not an OAuth refresh
        // token stored by this app. Calling authorize() again returns a fresh access token silently
        // while the user has already granted this scope. Only a result with a resolution needs UI.
        if (client.type == GoogleOAuthClientType.ANDROID) {
            val request = GCloudIdentityOAuth.authorizationRequest()
            val result = suspendCancellableCoroutine { continuation ->
                Identity.getAuthorizationClient(appContext).authorize(request)
                    .addOnSuccessListener { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
            if (result.hasResolution()) {
                error("Google Cloud access needs authorization. Open this OAuth client's settings and tap Authorize; later token renewals should happen automatically.")
            }
            val refreshedAccessToken = result.accessToken
                ?.takeIf(String::isNotBlank)
                ?: error("Google Identity did not return an access token.")
            storeAndroidOAuthToken(client, refreshedAccessToken)
            return refreshedAccessToken
        }

        val refresh = existing?.refreshToken?.takeIf(String::isNotBlank)
            ?: error("Authorize the Google OAuth client before connecting.")
        val payload = AppAuthTransactions.exchange(
            appContext,
            authHttp,
            AppAuthTransactions.token(
                RemoteMachineService.GOOGLE_TOKEN_ENDPOINT,
                client.clientId,
                mapOf("grant_type" to "refresh_token", "refresh_token" to refresh)
            ),
            AppAuthTransactions.authentication(
                if (client.clientSecret.isBlank()) "none" else "client_secret_post",
                client.clientSecret.takeIf(String::isNotBlank)
            )
        )
        val updated = tokenFromPayload(payload, existing)
        store.update { it.copy(oauthTokens = it.oauthTokens + (client.id to updated)) }
        return updated.accessToken
    }

    private fun clientFor(machine: RemoteMachine): GCloudOAuthClient = state.value.oauthClients
        .firstOrNull { it.id == machine.oauthClientId }
        ?: error("Select a Google OAuth client in this machine's settings.")

    private fun tokenFromPayload(payload: JsonObject, previous: RemoteOAuthToken?): RemoteOAuthToken {
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.longOrNull
        return RemoteOAuthToken(
            accessToken = payload["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { error("Google OAuth did not return an access token.") },
            refreshToken = payload["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: previous?.refreshToken.orEmpty(),
            tokenType = payload["token_type"]?.jsonPrimitive?.contentOrNull ?: previous?.tokenType ?: "Bearer",
            expiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1_000 }
                ?: previous?.expiresAt
        )
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

}
