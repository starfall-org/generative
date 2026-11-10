package org.starfall.generative.shared.model.preferences


import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How enabled MCP tools are attached to a model request. */
@Serializable
enum class McpAttachmentMode { DYNAMIC, CLASSIC }

/** Dynamic mode: what happens to the MCPs that do not fit under the minimum. */
@Serializable
enum class McpDynamicStrategy {
    /** The first MCPs below the minimum are attached classically; the rest are loaded on demand. */
    OVERFLOW,
    /** Every MCP is loaded on demand. */
    ALL
}

const val DEFAULT_MCP_DYNAMIC_MIN_MCPS = 5
const val DEFAULT_MCP_DYNAMIC_MIN_TOOLS = 20
const val DEFAULT_MCP_LIST_LIMIT = 100
const val DEFAULT_MCP_LOAD_LIMIT_OVERFLOW = 3
const val DEFAULT_MCP_LOAD_LIMIT_ALL = 7
const val MAX_MCP_DYNAMIC_MIN = 999
const val MAX_MCP_LIST_LIMIT = 1000
const val MAX_MCP_LOAD_LIMIT = 100

/**
 * "MCP Attachments" preferences, stored as one value.
 *
 * - [CLASSIC][McpAttachmentMode.CLASSIC]: every enabled MCP tool is sent; [includeToolDescription] decides whether
 *   the tool descriptions are sent too.
 * - [DYNAMIC][McpAttachmentMode.DYNAMIC]: once [minMcps] and [minTools] are reached, the model gets `list_mcps` and
 *   `connect_mcps` and connects MCP servers on demand. Below those thresholds everything is attached classically.
 */
@Serializable
data class McpAttachmentSettings(
    val mode: McpAttachmentMode = McpAttachmentMode.DYNAMIC,
    /** Sent with every classically attached MCP tool: send each tool's description. */
    val includeToolDescription: Boolean = true,
    /** Dynamic only: minimum number of enabled MCPs that activates it. 0 applies it to any number of MCPs. */
    val minMcps: Int = DEFAULT_MCP_DYNAMIC_MIN_MCPS,
    /** Dynamic only: minimum total number of enabled MCP tools that activates it. 0 disables this condition. */
    val minTools: Int = DEFAULT_MCP_DYNAMIC_MIN_TOOLS,
    val strategy: McpDynamicStrategy = McpDynamicStrategy.OVERFLOW,
    /** Maximum number of MCPs `list_mcps` returns. */
    val listLimit: Int = DEFAULT_MCP_LIST_LIMIT,
    /** Maximum number of MCPs that stay loaded together in [McpDynamicStrategy.OVERFLOW]. */
    val loadLimitOverflow: Int = DEFAULT_MCP_LOAD_LIMIT_OVERFLOW,
    /** Maximum number of MCPs that stay loaded together in [McpDynamicStrategy.ALL]. */
    val loadLimitAll: Int = DEFAULT_MCP_LOAD_LIMIT_ALL
) {
    /** Load limit of the selected [strategy]. */
    val loadLimit: Int
        get() = if (strategy == McpDynamicStrategy.ALL) loadLimitAll else loadLimitOverflow

    val defaultLoadLimit: Int
        get() = if (strategy == McpDynamicStrategy.ALL) DEFAULT_MCP_LOAD_LIMIT_ALL else DEFAULT_MCP_LOAD_LIMIT_OVERFLOW

    fun withLoadLimit(limit: Int): McpAttachmentSettings =
        if (strategy == McpDynamicStrategy.ALL) copy(loadLimitAll = limit) else copy(loadLimitOverflow = limit)

    /** Clamps every number into its valid range. */
    fun normalized(): McpAttachmentSettings = copy(
        minMcps = minMcps.coerceIn(0, MAX_MCP_DYNAMIC_MIN),
        minTools = minTools.coerceIn(0, MAX_MCP_DYNAMIC_MIN),
        listLimit = listLimit.coerceIn(1, MAX_MCP_LIST_LIMIT),
        loadLimitOverflow = loadLimitOverflow.coerceIn(1, MAX_MCP_LOAD_LIMIT),
        loadLimitAll = loadLimitAll.coerceIn(1, MAX_MCP_LOAD_LIMIT)
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun encode(settings: McpAttachmentSettings): String = json.encodeToString(settings.normalized())

        fun decode(raw: String?): McpAttachmentSettings =
            raw?.let { runCatching { json.decodeFromString<McpAttachmentSettings>(it).normalized() }.getOrNull() }
                ?: McpAttachmentSettings()
    }
}
