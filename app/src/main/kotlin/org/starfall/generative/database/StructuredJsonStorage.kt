package org.starfall.generative.database

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.starfall.generative.database.dao.StructuredJsonDao
import org.starfall.generative.database.entities.StructuredJsonDocumentEntity
import org.starfall.generative.database.entities.StructuredJsonNodeEntity

internal data class StructuredJsonDocument(
    val ownerType: String,
    val ownerId: String,
    val fieldName: String,
    val value: JsonElement
)

internal suspend fun StructuredJsonDao.replaceDocument(document: StructuredJsonDocument) {
    val old = getDocument(document.ownerType, document.ownerId, document.fieldName)
    val id = old?.id ?: UUID.randomUUID().toString()
    deleteDocument(id)
    insertDocument(StructuredJsonDocumentEntity(id, document.ownerType, document.ownerId, document.fieldName))
    val nodes = ArrayList<StructuredJsonNodeEntity>()
    var nextId = 0
    fun visit(value: JsonElement, parent: Int?, ordinal: Int, key: String?) {
        val idForNode = nextId++
        val (kind, scalar) = when (value) {
            JsonNull -> "null" to null
            is JsonObject -> "object" to null
            is JsonArray -> "array" to null
            is JsonPrimitive -> if (value.isString) "string" to value.content else {
                val raw = value.content
                when (raw) {
                    "true", "false" -> "boolean" to raw
                    else -> "number" to raw
                }
            }
        }
        nodes += StructuredJsonNodeEntity(id, idForNode, parent, ordinal, key, kind, scalar)
        when (value) {
            is JsonObject -> value.entries.forEachIndexed { index, (childKey, child) ->
                visit(child, idForNode, index, childKey)
            }
            is JsonArray -> value.forEachIndexed { index, child -> visit(child, idForNode, index, null) }
            else -> Unit
        }
    }
    visit(document.value, null, 0, null)
    insertNodes(nodes)
}

internal suspend fun StructuredJsonDao.readDocument(ownerType: String, ownerId: String, fieldName: String): JsonElement? {
    val document = getDocument(ownerType, ownerId, fieldName) ?: return null
    val nodes = getNodes(document.id).associateBy { it.nodeId }
    val childrenByParent = nodes.values.groupBy { it.parentNodeId }
    val visiting = hashSetOf<Int>()
    fun read(nodeId: Int): JsonElement {
        // A malformed imported/legacy graph must fail just this document, never recurse
        // indefinitely until StackOverflowError poisons the entire provider list.
        check(visiting.add(nodeId)) { "Cyclic structured JSON document $ownerType/$ownerId/$fieldName" }
        check(visiting.size <= 128) { "Structured JSON depth exceeded for $ownerType/$ownerId/$fieldName" }
        val node = requireNotNull(nodes[nodeId]) { "Missing structured JSON node $nodeId" }
        val children = childrenByParent[node.nodeId].orEmpty().sortedBy { it.ordinal }
        val value = when (node.nodeKind) {
            "null" -> JsonNull
            "string" -> JsonPrimitive(node.scalarValue.orEmpty())
            "boolean" -> JsonPrimitive(node.scalarValue == "true")
            "number" -> Json.parseToJsonElement(node.scalarValue.orEmpty())
            "array" -> buildJsonArray { children.forEach { add(read(it.nodeId)) } }
            "object" -> buildJsonObject {
                children.forEach { child -> put(requireNotNull(child.objectKey), read(child.nodeId)) }
            }
            else -> error("Unknown structured JSON node kind ${node.nodeKind}")
        }
        visiting.remove(nodeId)
        return value
    }
    return nodes.values.firstOrNull { it.parentNodeId == null }?.let { read(it.nodeId) }
}
