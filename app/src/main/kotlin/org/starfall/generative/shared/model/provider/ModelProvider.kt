package org.starfall.generative.shared.model.provider

import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.CredentialLoadState

import kotlinx.serialization.Serializable

@Serializable
data class ModelProvider(
    val id: String,
    val name: String,
    val type: ModelApiType,
    val auth: Authorization = Authorization(),
    val icon: String? = null,
    val baseUrl: String,
    val config: ProviderConfiguration = ProviderConfiguration(),
    val sortOrder: Int = Int.MAX_VALUE,
    val groupId: String? = null,
    @kotlinx.serialization.Transient val credentialState: CredentialLoadState = CredentialLoadState.LOADED,
    val isHidden: Boolean = false
)
