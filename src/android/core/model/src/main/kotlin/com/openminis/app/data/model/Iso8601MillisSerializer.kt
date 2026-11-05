package com.openminis.app.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * JVM-safe copy of the backup ISO-8601 millis wire format so `:core:model`
 * does not depend on `app.backup`. Writes UTC seconds with a literal `Z`;
 * reads ISO strings or legacy epoch-millis numbers.
 *
 * [T-iso8601-serializer-single-source] Formatting and parsing now delegate to
 * [IsoTimeCore] — the same single implementation the app-side
 * `com.openminis.app.util.IsoTime` and `app.backup`'s `Iso8601MillisSerializer`
 * (typealias) use. The old private 5-candidate [parseFlexible] chain here was
 * a strict subset of the app one and has been retired; the wire format is
 * unchanged (same `yyyy-MM-dd'T'HH:mm:ss'Z'` pattern, same descriptor name).
 */
object Iso8601MillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601Millis", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(IsoTimeCore.formatUtcSeconds(value))
    }

    override fun deserialize(decoder: Decoder): Long {
        val jd = decoder as? JsonDecoder
            ?: return IsoTimeCore.parseFlexible(decoder.decodeString()) ?: 0L
        val el = jd.decodeJsonElement()
        val prim = el as? JsonPrimitive ?: return 0L
        prim.longOrNull?.let { return it }
        return IsoTimeCore.parseFlexible(prim.content) ?: 0L
    }

    /** Shared parse chain — kept as a member so call sites read naturally. */
    internal fun parseFlexible(s: String): Long? = IsoTimeCore.parseFlexible(s)
}

object Iso8601MillisNullableSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601MillisNullable", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull()
        else Iso8601MillisSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val jd = decoder as? JsonDecoder
            ?: return Iso8601MillisSerializer.deserialize(decoder)
        val el = jd.decodeJsonElement()
        if (el is JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        prim.longOrNull?.let { return it }
        return IsoTimeCore.parseFlexible(prim.content)
    }
}
