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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * JVM-safe copy of the backup ISO-8601 millis wire format so `:core:model`
 * does not depend on `app.backup` / `IsoTime`. Writes UTC seconds with a
 * literal `Z`; reads ISO strings or legacy epoch-millis numbers.
 */
object Iso8601MillisSerializer : KSerializer<Long> {
    private val UTC_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601Millis", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(UTC_SECONDS.format(Instant.ofEpochMilli(value)))
    }

    override fun deserialize(decoder: Decoder): Long {
        val jd = decoder as? JsonDecoder
            ?: return parseFlexible(decoder.decodeString()) ?: 0L
        val el = jd.decodeJsonElement()
        val prim = el as? JsonPrimitive ?: return 0L
        prim.longOrNull?.let { return it }
        return parseFlexible(prim.content) ?: 0L
    }

    internal fun parseFlexible(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        try { return Instant.parse(t).toEpochMilli() } catch (_: Throwable) {}
        try { return OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: Throwable) {}
        try { return Instant.from(UTC_SECONDS.parse(t)).toEpochMilli() } catch (_: Throwable) {}
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Throwable) {}
        try {
            return LocalDate.parse(t).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Throwable) {}
        return null
    }
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
        return Iso8601MillisSerializer.parseFlexible(prim.content)
    }
}
