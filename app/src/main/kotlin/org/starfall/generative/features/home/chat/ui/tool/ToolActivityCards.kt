package org.starfall.generative.features.home.chat.ui.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin
import org.starfall.generative.shared.subfeatures.media.ui.MediaViewer
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.database.preferences.WordWrapMode
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.features.home.shared.ui.LocalToolSheetState
import org.starfall.generative.features.home.shared.ui.ToolSheetState
import org.starfall.generative.features.home.shared.ui.ToolDetailsSheetHost

@Composable
fun ToolActivityCards(activities: List<ToolActivity>) {
    val hostedState = LocalToolSheetState.current
    val localState = remember { ToolSheetState() }
    val sheetState = hostedState ?: localState
    val visibleActivities = activities.filterNot { it.name.endsWith(": connect") || it.name == "send_file" }

    if (visibleActivities.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            visibleActivities.forEach { activity ->
                key(activity.id) {
                    ToolActivityLabel(
                        activity = activity,
                        onClick = { sheetState.selectedId = activity.id }
                    )
                }
            }
        }
    }

    if (hostedState == null) {
        ToolDetailsSheetHost(localState, activities)
    }
}

@Composable
private fun ToolActivityLabel(
    activity: ToolActivity,
    onClick: () -> Unit
) {
    val running = activity.status == "running"
    val phase = if (running) {
        val transition = rememberInfiniteTransition(label = "tool-running")
        val wavePhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (Math.PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "tool-alpha"
        )
        wavePhase
    } else {
        0f
    }
    val color = MaterialTheme.colorScheme.onSurface
    val displayName = when (activity.name) {
        "generate_image" -> stringResource(if (running) R.string.tool_generating_image else R.string.tool_generate_image)
        "generate_music" -> stringResource(if (running) R.string.tool_generating_music else R.string.tool_generate_music)
        "generate_video" -> stringResource(if (running) R.string.tool_generating_video else R.string.tool_generate_video)
        else -> activity.name.replace('_', ' ')
    }
    val label = buildAnnotatedString {
        displayName.forEachIndexed { index, character ->
            val alpha = if (running) 0.4f + 0.6f * ((sin(phase - index * 0.5f) + 1f) / 2f) else 1f
            withStyle(SpanStyle(color = color.copy(alpha = alpha))) { append(character) }
        }
    }
    Text(
        text = label,
        style = if (running) MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp) else MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 7.dp, bottom = 7.dp)
    )
}
