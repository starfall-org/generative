package org.starfall.generative.features.chatoptions.ui

import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.starfall.generative.shared.ui.text.SelectionContainer
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.text.messageMarkdownTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstructionPreview(
    instruction: SavedInstruction,
    onEdit: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
                title = {
                    Text(
                        instruction.name.ifBlank { stringResource(R.string.instruction_preview_title) },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        SharedIcon(
                            Icons.Outlined.Edit,
                            stringResource(R.string.instruction_preview_edit)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InstructionPreviewMessage(instruction.role, instruction.content)
        }
    }
}

@Composable
private fun InstructionPreviewMessage(role: ChatRole, content: String) {
    val isUser = role == ChatRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            // Plain text on the screen background: no bubble behind the message text.
            Column(
                modifier = Modifier
                    .fillMaxWidth(if (role == ChatRole.SYSTEM) 1f else 0.92f)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (content.isBlank()) {
                    Text(
                        stringResource(R.string.instruction_preview_empty),
                        style = messageMarkdownTypography().paragraph
                    )
                } else {
                    SelectionContainer {
                        InstructionMarkdown(content)
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionMarkdown(content: String) {
    var state by remember(content) { mutableStateOf<State>(State.Loading()) }
    LaunchedEffect(content) {
        state = withContext(Dispatchers.Default) { parseMarkdown(content) }
    }
    Markdown(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(),
        typography = messageMarkdownTypography(),
        padding = markdownPadding(block = 8.dp)
    )
}
