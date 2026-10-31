package org.starfall.generative.shared.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.starfall.generative.shared.model.api.ModelApiType

/** Transient, non-persistent device authorization instruction for the provider editor. */
internal data class DeviceCodeChallenge(
    val providerType: ModelApiType,
    val userCode: String,
    val verificationUrl: String
)

internal object DeviceCodeSignInState {
    private val active = MutableStateFlow<DeviceCodeChallenge?>(null)
    val challenge = active.asStateFlow()

    fun show(challenge: DeviceCodeChallenge) { active.value = challenge }
    fun clear(challenge: DeviceCodeChallenge) {
        if (active.value == challenge) active.value = null
    }
}
