package app.meanwhile.data.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime

/**
 * JSON used for Supabase rows and CSV export: snake_case column names, defaults always encoded.
 * Room columns stay camelCase locally; this is the only place the remote naming lives.
 */
@OptIn(ExperimentalSerializationApi::class)
val RecordJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/** General-purpose JSON for json-text columns, profiles and AI payloads. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

fun parseInstantMillis(text: String): Long = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    .getOrElse { Instant.parse(text).toEpochMilli() }

fun isoOf(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

/** Epoch millis locally ↔ ISO-8601 `timestamptz` remotely. */
object EpochMillisIso : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.meanwhile.EpochMillisIso", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(isoOf(value))
    override fun deserialize(decoder: Decoder): Long = parseInstantMillis(decoder.decodeString())
}

@OptIn(ExperimentalSerializationApi::class)
object NullableEpochMillisIso : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.meanwhile.NullableEpochMillisIso", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(isoOf(value))
    }

    override fun deserialize(decoder: Decoder): Long? =
        if (decoder.decodeNotNullMark()) parseInstantMillis(decoder.decodeString()) else decoder.decodeNull()
}

/** JSON stored as text in Room ↔ real JSON (`jsonb`) remotely. */
object JsonText : KSerializer<String> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: String) {
        val json = encoder as? JsonEncoder ?: error("JsonText only supports JSON")
        val element = runCatching { AppJson.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: error("JsonText only supports JSON")
        return json.decodeJsonElement().toString()
    }
}
