package org.starfall.generative.features.home.chat.ui

import androidx.compose.runtime.*

@Stable
class ChatAttachmentSelection {
    val attachments = mutableStateOf<List<String>>(emptyList())
    var requestId by mutableIntStateOf(0)
        private set
    // Shared text is consumed by the composer, which owns the actual editable TextFieldValue.
    var pendingExternalText by mutableStateOf<String?>(null)
        private set

    fun queueExternalText(text: String) {
        pendingExternalText = listOfNotNull(pendingExternalText, text).joinToString("\n\n")
    }

    fun takeExternalText(): String? = pendingExternalText.also { pendingExternalText = null }

    fun addAttachments(references: Collection<String>) {
        attachments.value = (attachments.value + references).distinct()
    }

    fun requestChatMode() { requestId++ }

    fun toggle(reference: String) {
        attachments.value = if (reference in attachments.value) attachments.value - reference
            else (attachments.value + reference).distinct()
        requestChatMode()
    }

    fun clear() { attachments.value = emptyList() }
}

val LocalChatAttachmentSelection = compositionLocalOf<ChatAttachmentSelection?> { null }
