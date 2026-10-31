package org.starfall.generative.integrations.modelapis.account

import android.content.Context
import org.starfall.generative.integrations.security.oauthRecordStorage
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class AccountTokenState(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Long? = null,
    val accountId: String? = null,
    val email: String? = null,
    val projectId: String? = null,
    val codeAssistBaseUrl: String? = null,
    val deviceToken: String? = null,
    val deviceTokenExpiresAt: Long? = null,
    val tokenEndpoint: String? = null,
    val apiBaseUrl: String? = null
)

internal class AccountTokenStore(context: Context, namespace: String) {
    private val namespace = "generative.$namespace.oauth"
    private val records by lazy { oauthRecordStorage(context.applicationContext) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(providerId: String): AccountTokenState? {
        val encrypted = records.get(namespace, providerId) ?: return null
        return runCatching {
            json.decodeFromString<AccountTokenState>(decrypt(encrypted))
        }.getOrNull()
    }

    fun save(providerId: String, token: AccountTokenState) {
        records.put(namespace, providerId, encrypt(json.encodeToString(token)))
    }

    fun delete(providerId: String) {
        records.remove(namespace, providerId)
    }

    fun exportAll(): Map<String, JsonObject> = records.all(namespace).mapNotNull { (providerId, value) ->
        val encrypted = value
        runCatching {
            providerId to json.encodeToJsonElement<AccountTokenState>(json.decodeFromString(decrypt(encrypted))).jsonObject
        }.getOrNull()
    }.toMap()

    fun importAll(credentials: Map<String, JsonObject>) {
        val encoded = credentials.mapValues { (_, value) ->
            val token = json.decodeFromJsonElement<AccountTokenState>(value)
            encrypt(json.encodeToString(token))
        }
        records.replace(namespace, encoded)
    }

    private fun key(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return@synchronized it }
        check(create) { "Account credential key is unavailable." }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    private fun decrypt(value: String): String {
        require(value.startsWith(PREFIX)) { "Unsupported Account credential format." }
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size >= 28) { "Invalid Account credential payload." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(false),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    private val keyAlias = "generative.$namespace.oauth.v1"

    private companion object {
        const val PREFIX = "keystore:v1:"
        val KEY_LOCK = Any()
    }
}
