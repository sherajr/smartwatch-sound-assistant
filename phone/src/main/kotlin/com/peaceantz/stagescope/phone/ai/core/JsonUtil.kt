package com.peaceantz.stagescope.phone.ai.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient, null-safe accessors for parsing *vendor* JSON whose shape we do not control. */
internal val VendorJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
internal fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

internal operator fun JsonObject.get(vararg path: String): JsonElement? {
    var cur: JsonElement? = this
    for (p in path) cur = (cur as? JsonObject)?.get(p) ?: return null
    return cur
}

internal fun parseObject(text: String): JsonObject? =
    runCatching { VendorJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
