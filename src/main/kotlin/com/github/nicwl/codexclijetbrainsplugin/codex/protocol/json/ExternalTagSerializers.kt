package com.github.nicwl.codexclijetbrainsplugin.codex.protocol.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generic transformer for Rust/serde-style externally-tagged enums to Kotlinx internal tagging.
 *
 * Incoming shape: { "Tag": { ...fields } } or { "Tag": <primitiveOrArray> }
 * Transforms to: { "type": "Tag", ...fields } or { "type": "Tag", <primitiveField>: <value> }
 *
 * Outgoing shape is inverted back to the external tagging used by codex-rs.
 */
open class ExternalTagToInternalTypeSerializer<T : Any>(
    tSerializer: KSerializer<T>,
    private val discriminator: String = "type",
) : JsonTransformingSerializer<T>(tSerializer) {

    // Override to normalize tag names (e.g., lowercase or snake_case -> PascalCase).
    protected open fun normalizeTagForDecode(tag: String): String = tag

    // Override to choose the field name to wrap primitive payloads for a given tag.
    protected open fun primitiveFieldFor(tag: String): String = "value"

    // Override to choose the field name to wrap object payloads for a given tag. Return null to merge fields.
    protected open fun objectFieldFor(tag: String): String? = null

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        if (obj.size != 1) return element
        val (rawTag, payload) = obj.entries.first()
        val tag = normalizeTagForDecode(rawTag)
        return if (payload is JsonObject) {
            val wrapField = objectFieldFor(tag)
            if (wrapField != null) {
                // Wrap object payload as nested under wrapField
                buildJsonObject {
                    put(discriminator, JsonPrimitive(tag))
                    put(wrapField, payload)
                }
            } else {
                // Merge discriminator into payload
                val withType = HashMap<String, JsonElement>(payload.size + 1)
                withType.putAll(payload)
                withType[discriminator] = JsonPrimitive(tag)
                JsonObject(withType)
            }
        } else {
            // Wrap primitive/array payload into an object under a field specific to the variant
            buildJsonObject {
                put(discriminator, JsonPrimitive(tag))
                put(primitiveFieldFor(tag), payload)
            }
        }
    }

    override fun transformSerialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val disc = obj[discriminator] as? JsonPrimitive ?: return element
        val tag = disc.content
        val content = LinkedHashMap<String, JsonElement>(obj.size)
        // Remove the discriminator from the object
        for ((k, v) in obj) if (k != discriminator) content[k] = v

        val primKey = primitiveFieldFor(tag)
        val wrapObjectKey = objectFieldFor(tag)
        val singlePrimitive = content.size == 1 && content.containsKey(primKey)
        val singleWrappedObject = wrapObjectKey != null && content.size == 1 && content.containsKey(wrapObjectKey)
        return if (singlePrimitive) {
            // {"type": "Tag", "<primKey>": value} -> {"Tag": value}
            buildJsonObject { put(tag, content[primKey]!!) }
        } else if (singleWrappedObject) {
            // {"type": "Tag", "<wrapObjectKey>": { ... }} -> {"Tag": { ... }}
            buildJsonObject { put(tag, content[wrapObjectKey]!!) }
        } else {
            // {"type": "Tag", ...fields} -> {"Tag": { ...fields }}
            buildJsonObject { putJsonObject(tag) { for ((k, v) in content) put(k, v) } }
        }
    }
}
