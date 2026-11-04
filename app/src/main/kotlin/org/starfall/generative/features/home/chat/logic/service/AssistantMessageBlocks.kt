package org.starfall.generative.features.home.chat.logic.service

import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.hasVisibleAssistantBlock

internal data class AssistantActionBlock(val offset: Int, val items: List<AssistantTimelineItem>)

internal sealed interface AssistantTimelineItem {
    val contentOffset: Int

    data class Thinking(
        override val contentOffset: Int,
        val reasoning: String,
        val start: Int,
        val end: Int
    ) : AssistantTimelineItem

    data class Tool(val activity: ToolActivity) : AssistantTimelineItem {
        override val contentOffset: Int get() = activity.contentOffset
    }
}

internal fun buildAssistantTimeline(version: MessageVersion): List<AssistantTimelineItem> {
    val activities = version.toolActivity.filterNot { it.name.endsWith(": connect") }
    val reasoning = version.reasoningContent.orEmpty()

    if (activities.isNotEmpty() && activities.all { it.reasoningOffset == null }) {
        return buildList {
            if (reasoning.isNotBlank()) add(AssistantTimelineItem.Thinking(version.reasoningContentOffset, reasoning, 0, reasoning.length))
            activities.forEach { add(AssistantTimelineItem.Tool(it)) }
        }.sortedBy { it.contentOffset }
    }

    val timeline = mutableListOf<AssistantTimelineItem>()
    var reasoningCursor = 0
    activities.forEach { activity ->
        val reasoningEnd = (activity.reasoningOffset ?: reasoningCursor)
            .coerceIn(reasoningCursor, reasoning.length)
        if (reasoningEnd > reasoningCursor) {
            timeline += AssistantTimelineItem.Thinking(
                activity.contentOffset.coerceIn(0, version.content.length),
                reasoning.substring(reasoningCursor, reasoningEnd),
                reasoningCursor, reasoningEnd
            )
        }
        timeline += AssistantTimelineItem.Tool(activity)
        reasoningCursor = reasoningEnd
    }
    if (reasoningCursor < reasoning.length) {
        timeline += AssistantTimelineItem.Thinking(
            activities.lastOrNull()?.contentOffset?.coerceIn(0, version.content.length)
                ?: version.reasoningContentOffset,
            reasoning.substring(reasoningCursor), reasoningCursor, reasoning.length
        )
    }
    return timeline.sortedBy { it.contentOffset }
}

internal fun buildAssistantActionBlocks(version: MessageVersion): List<AssistantActionBlock> {
    val timeline = buildAssistantTimeline(version)
    if (timeline.isEmpty()) return emptyList()

    val blocks = mutableListOf<AssistantActionBlock>()
    var groupOffset = timeline.first().contentOffset.coerceIn(0, version.content.length)
    var previousOffset = groupOffset
    var groupItems = mutableListOf(timeline.first())

    timeline.drop(1).forEach { item ->
        val itemOffset = item.contentOffset.coerceIn(previousOffset, version.content.length)
        val hasVisibleContentBetween = itemOffset > previousOffset && version.content.substring(
            previousOffset.coerceAtMost(version.content.length),
            itemOffset.coerceAtMost(version.content.length)
        ).isNotBlank()

        val previousTool = groupItems.filterIsInstance<AssistantTimelineItem.Tool>().lastOrNull()
        val currentTool = item as? AssistantTimelineItem.Tool
        val beforeGroup = previousTool?.activity?.actionGroup
        val afterGroup = currentTool?.activity?.actionGroup
        val differentGroups = beforeGroup != null && afterGroup != null && beforeGroup != afterGroup
        if (hasVisibleContentBetween || differentGroups) {
            blocks += AssistantActionBlock(
                offset = groupOffset,
                items = groupItems.toList()
            )
            groupOffset = itemOffset
            groupItems = mutableListOf()
        }

        groupItems += item
        previousOffset = itemOffset
    }

    blocks += AssistantActionBlock(
        offset = groupOffset,
        items = groupItems.toList()
    )
    return blocks
}

internal enum class AssistantBlockKind { ACTIONS, MEDIA, ERROR }

internal data class AssistantVisibleBlock(
    val number: Int,
    val kind: AssistantBlockKind,
    val offset: Int,
    val actionIndex: Int? = null,
    val key: String = ""
) {
    val placeholder: String get() = when (kind) {
        AssistantBlockKind.ACTIONS -> "[ActionsBlock$number]"
        AssistantBlockKind.MEDIA -> "[MediaBlock$number]"
        AssistantBlockKind.ERROR -> "[ErrorBlock]"
    }
}

/** One numbered placeholder per displayed block, in visual reading order. */
internal fun assistantVisibleBlocks(
    version: MessageVersion,
    isStreaming: Boolean = false
): List<AssistantVisibleBlock> {
    val blocks = mutableListOf<AssistantVisibleBlock>()
    buildAssistantActionBlocks(version).forEachIndexed { index, block ->
        val toolId = block.items.filterIsInstance<AssistantTimelineItem.Tool>()
            .firstOrNull()?.activity?.id
        val groupId = toolId ?: "thinking:$index"
        if (block.items.any { item ->
            item is AssistantTimelineItem.Thinking ||
                item is AssistantTimelineItem.Tool && item.activity.name != "send_file"
        }) blocks += AssistantVisibleBlock(0, AssistantBlockKind.ACTIONS,
            block.offset, index, "actions:$groupId")
        if (block.items.filterIsInstance<AssistantTimelineItem.Tool>().any { tool ->
            tool.activity.responsePreviewLimit == 0 &&
                tool.activity.files.any { !it.endsWith(".json", ignoreCase = true) } ||
                (tool.activity.name in setOf("generate_image", "generate_video") &&
                    (tool.activity.status == "error" || isStreaming && tool.activity.status == "running"))
        }) {
            val key = block.items.filterIsInstance<AssistantTimelineItem.Tool>()
                .firstOrNull()?.activity?.id
            blocks += AssistantVisibleBlock(0, AssistantBlockKind.MEDIA,
                key?.let { version.mediaContentOffsets[it] } ?: block.offset,
                index, "media:$groupId")
        }
    }
    if (version.files.isNotEmpty()) {
        blocks += AssistantVisibleBlock(0, AssistantBlockKind.MEDIA,
            version.filesContentOffset ?: version.content.length, key = "media:files")
    }
    if (blocks.isEmpty() && version.toolActivity.any { it.hasVisibleAssistantBlock() }) {
        blocks += AssistantVisibleBlock(0, AssistantBlockKind.ACTIONS, 0, key = "actions:internal")
    }
    if (!version.generationError.isNullOrBlank()) {
        blocks += AssistantVisibleBlock(0, AssistantBlockKind.ERROR,
            version.content.length, key = "error:generation")
    }
    return blocks.sortedWith(compareBy<AssistantVisibleBlock> { it.offset }
        .thenBy { it.kind == AssistantBlockKind.ERROR }
        .thenBy { version.blockOrder[it.key] ?: Int.MAX_VALUE })
        .mapIndexed { i, block -> block.copy(number = i + 1) }
}

/** Strip only known markers. Omitted markers preserve their blocks at the start of Content. */
internal fun editedAssistantVersion(
    message: org.starfall.generative.shared.model.StoredMessage,
    draft: String,
    files: List<String>,
    timestamp: String
): MessageVersion {
    val previous = message.activeVersion
    val blocks = assistantVisibleBlocks(previous)
    val positions = mutableMapOf<Int, Int>()
    val explicitOrder = mutableListOf<String>()
    val result = StringBuilder()
    var cursor = 0
    val markers = Regex("""\[(?:ActionsBlock\d+|MediaBlock\d+|ErrorBlock)]""")
    markers.findAll(draft).forEach { match ->
        val block = blocks.firstOrNull { it.placeholder == match.value }
        if (block != null) {
            result.append(draft, cursor, match.range.first)
            if (positions.putIfAbsent(block.number, result.length) == null)
                explicitOrder += block.key
            cursor = match.range.last + 1
        }
    }
    result.append(draft, cursor, draft.length)
    val content = result.toString()
    // Edit may add attachments, but only the padding Delete menu removes an existing block.
    val retainedFiles = (previous.files + files).distinct()
    val actionBlocks = buildAssistantActionBlocks(previous)
    val actionOffsets = actionBlocks.indices.associateWith { group ->
        val action = blocks.firstOrNull { it.actionIndex == group && it.kind == AssistantBlockKind.ACTIONS }
        val media = blocks.firstOrNull { it.actionIndex == group && it.kind == AssistantBlockKind.MEDIA }
        action?.number?.let(positions::get) ?: media?.number?.let(positions::get) ?: 0
    }
    val originalGroups = previous.toolActivity.associate { it.id to (actionBlocks.indexOfFirst { block ->
        block.items.any { item -> item is AssistantTimelineItem.Tool && item.activity.id == it.id }
    }) }
    val mediaOffsets = blocks.filter { it.kind == AssistantBlockKind.MEDIA && it.actionIndex != null }
        .mapNotNull { block ->
            val key = actionBlocks[block.actionIndex!!].items
                .filterIsInstance<AssistantTimelineItem.Tool>().firstOrNull()?.activity?.id
            key?.let { it to (positions[block.number] ?: 0) }
        }.toMap()
    val movedTools = previous.toolActivity.map { tool ->
        val group = originalGroups[tool.id] ?: -1
        if (group < 0) tool.copy(contentOffset = 0)
        else tool.copy(contentOffset = actionOffsets[group] ?: 0,
            actionGroup = group.takeIf { actionBlocks.size > 1 })
    }
    val thinkingGroup = actionBlocks.indexOfFirst { block ->
        block.items.any { it is AssistantTimelineItem.Thinking && it.start == 0 }
    }
    return previous.copy(
        content = content,
        timestamp = timestamp,
        files = retainedFiles,
        filesContentOffset = if (retainedFiles.isNotEmpty()) blocks.firstOrNull {
            it.kind == AssistantBlockKind.MEDIA && it.actionIndex == null
        }?.let { positions[it.number] ?: 0 } ?: 0 else null,
        mediaContentOffsets = mediaOffsets,
        blockOrder = (blocks.filter { it.number !in positions }.map { it.key } + explicitOrder)
            .mapIndexed { index, key -> key to index }.toMap(),
        toolActivity = movedTools,
        reasoningContentOffset = actionOffsets[thinkingGroup] ?: 0,
        generationError = null,
        generationInterrupted = false
    )
}

/** Delete one numbered visual block, without touching Content or any other block. */
internal fun deleteAssistantBlock(version: MessageVersion, number: Int): MessageVersion {
    val target = assistantVisibleBlocks(version).firstOrNull { it.number == number } ?: return version
    if (target.kind == AssistantBlockKind.ERROR) return version.copy(generationError = null, generationInterrupted = false)
    if (target.kind == AssistantBlockKind.MEDIA) {
        val group = target.actionIndex?.let { buildAssistantActionBlocks(version).getOrNull(it) }
        val toolIds = group?.items?.filterIsInstance<AssistantTimelineItem.Tool>()
            ?.map { it.activity.id }?.toSet().orEmpty()
        return if (group == null) version.copy(
            files = emptyList(), filesContentOffset = null
        ) else version.copy(
            toolActivity = version.toolActivity.mapNotNull { tool ->
                when {
                    tool.id !in toolIds -> tool
                    tool.name == "send_file" -> null
                    else -> tool.copy(files = tool.files.filter {
                        it.endsWith(".json", ignoreCase = true)
                    })
                }
            },
            mediaContentOffsets = version.mediaContentOffsets - toolIds
        )
    }
    val group = target.actionIndex?.let { buildAssistantActionBlocks(version).getOrNull(it) }
        ?: return version.copy(toolActivity = emptyList())
    val toolIds = group.items.filterIsInstance<AssistantTimelineItem.Tool>().map { it.activity.id }.toSet()
    val ranges = group.items.filterIsInstance<AssistantTimelineItem.Thinking>()
        .map { it.start until it.end }
    val reasoning = version.reasoningContent.orEmpty()
    val removedIndices = ranges.flatMap { range -> range.toList() }.toSet()
    val retainedReasoning = reasoning.filterIndexed { index, _ -> index !in removedIndices }
    val media = assistantVisibleBlocks(version).firstOrNull {
        it.kind == AssistantBlockKind.MEDIA && it.actionIndex == target.actionIndex
    }
    // Removing Actions must not silently delete their independently numbered Media block.
    val retainedFiles = if (media == null) emptyList() else group.items
        .filterIsInstance<AssistantTimelineItem.Tool>()
        .flatMap { tool -> tool.activity.files.filterNot { it.endsWith(".json", ignoreCase = true) } }
    return version.copy(
        toolActivity = version.toolActivity.filterNot { it.id in toolIds }.map { tool ->
            tool.copy(reasoningOffset = tool.reasoningOffset?.let { offset ->
                (offset - removedIndices.count { it < offset }).coerceAtLeast(0)
            })
        },
        files = version.files + retainedFiles,
        filesContentOffset = if (version.files.isEmpty() && retainedFiles.isNotEmpty()) media?.offset
            else version.filesContentOffset,
        reasoningContent = retainedReasoning.ifEmpty { null }
    )
}
