package com.github.nicwl.codexclijetbrainsplugin.codex.protocol.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.*
import kotlin.reflect.KClass
import kotlinx.serialization.serializer
import kotlinx.serialization.InternalSerializationApi

/**
 * Transform Serde-style externally-tagged JSON into internally-tagged JSON and back.
 * Works with any T that itself is serialized as internally-tagged with `typeKey`.
 */
open class ExternalTaggingSerializer<T : Any>(
    private val delegateSupplier: () -> KSerializer<T>,
    private val typeKey: String = "type",
    private val unitPayloads: Set<JsonElement> = setOf(JsonNull, JsonObject(emptyMap()))
) : KSerializer<T> {

    private val delegate: KSerializer<T> by lazy(delegateSupplier)
    override val descriptor: SerialDescriptor by lazy { delegate.descriptor }

    private fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        if (obj.size != 1) return element
        val (tag, payload) = obj.entries.first()

        return when (payload) {
            is JsonObject -> buildJsonObject {
                put(typeKey, JsonPrimitive(tag))
                payload.forEach { (k, v) -> put(k, v) }
            }
            else -> {
                // Treat null/{} as a unit variant; map scalars under "value" if needed.
                if (payload in unitPayloads) buildJsonObject {
                    put(typeKey, JsonPrimitive(tag))
                } else buildJsonObject {
                    put(typeKey, JsonPrimitive(tag))
                    put("value", payload)
                }
            }
        }
    }

    private fun transformSerialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val tag = obj[typeKey]?.jsonPrimitive?.content ?: return element
        val rest = obj.toMutableMap().also { it.remove(typeKey) }
        val payload: JsonElement = if (rest.isEmpty()) JsonNull else JsonObject(rest)
        return buildJsonObject { put(tag, payload) }
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): T {
        val input = decoder as? JsonDecoder
            ?: return delegate.deserialize(decoder)
        val element = input.decodeJsonElement()
        val transformed = transformDeserialize(element)
        return input.json.decodeFromJsonElement(delegate, transformed)
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: T) {
        val output = encoder as? JsonEncoder
            ?: return delegate.serialize(encoder, value)
        val element = output.json.encodeToJsonElement(delegate, value)
        val transformed = transformSerialize(element)
        output.encodeJsonElement(transformed)
    }
}

private class LazyDelegateSerializer<T>(private val supplier: () -> KSerializer<T>) : KSerializer<T> {
    private val delegate: KSerializer<T> by lazy(supplier)
    override val descriptor: SerialDescriptor get() = delegate.descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): T = delegate.deserialize(decoder)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: T) = delegate.serialize(encoder, value)
}

/** Convenience factory so you can write: ExternalTagging.forType(Shape::class) */
object ExternalTagging {
    @OptIn(InternalSerializationApi::class)
    fun <T : Any> forType(base: KClass<T>, typeKey: String = "type"): KSerializer<T> =
        ExternalTaggingSerializer(delegateSupplier = { base.serializer() }, typeKey = typeKey)
}
