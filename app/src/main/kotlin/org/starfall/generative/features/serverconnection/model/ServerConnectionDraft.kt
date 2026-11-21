package org.starfall.generative.features.serverconnection.model

internal class ServerConnectionDraft(
    val name: String,
    val transport: String,
    val invitation: String,
    val sshMachineId: String,
    val endpoint: String,
    val username: String,
    val password: String,
    val authUrl: String,
    val allowLocalProviders: Boolean,
)
