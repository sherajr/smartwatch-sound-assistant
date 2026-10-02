package com.peaceantz.stagescope.phone.tools

import com.peaceantz.stagescope.phone.ai.core.arr
import com.peaceantz.stagescope.phone.ai.core.bool
import com.peaceantz.stagescope.phone.ai.core.double
import com.peaceantz.stagescope.phone.ai.core.long
import com.peaceantz.stagescope.phone.ai.core.obj
import com.peaceantz.stagescope.phone.ai.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A small JSON-Schema subset validator (type, required, enum, minLength/maxLength, minimum/maximum,
 * items/maxItems, additionalProperties:false). Tool arguments are validated here, **outside the
 * model**, against the same schema the model was shown: a complete, well-typed argument object or
 * nothing runs. Unknown properties are rejected so a model cannot smuggle in parameters nobody
 * defined (for example an `approved: true` flag -- no tool has one, and approval only ever comes
 * from the app's own UI).
 */
object SchemaValidator {

    fun validate(schema: JsonObject, value: JsonElement, path: String = "arguments"): List<String> {
        val errors = ArrayList<String>()
        check(schema, value, path, errors)
        return errors
    }

    private fun check(schema: JsonObject, value: JsonElement, path: String, errors: MutableList<String>) {
        val type = schema["type"].str()
        when (type) {
            "object" -> {
                val obj = value as? JsonObject ?: return run { errors += "$path must be an object" }
                val props = schema["properties"].obj() ?: JsonObject(emptyMap())
                schema["required"].arr()?.forEach { r ->
                    val key = r.str() ?: return@forEach
                    val v = obj[key]
                    if (v == null || v is JsonNull) errors += "$path.$key is required"
                }
                if (schema["additionalProperties"].bool() == false || schema["additionalProperties"] == null) {
                    for (k in obj.keys) if (k !in props) errors += "$path.$k is not a known parameter"
                }
                for ((k, sub) in props) {
                    val v = obj[k] ?: continue
                    if (v is JsonNull) continue
                    check(sub.obj() ?: continue, v, "$path.$k", errors)
                }
            }
            "string" -> {
                val p = value as? JsonPrimitive
                if (p == null || !p.isString) return run { errors += "$path must be a string" }
                val s = p.content
                schema["minLength"].long()?.let { if (s.length < it) errors += "$path must be at least $it characters" }
                schema["maxLength"].long()?.let { if (s.length > it) errors += "$path must be at most $it characters (it was ${s.length})" }
                if (s.any { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }) errors += "$path contains control characters"
                schema["enum"].arr()?.let { allowed -> if (allowed.none { it.str() == s }) errors += "$path must be one of ${allowed.mapNotNull { it.str() }}" }
            }
            "integer" -> {
                val p = value as? JsonPrimitive
                val n = p?.takeIf { !it.isString }?.long()
                if (n == null) return run { errors += "$path must be an integer" }
                schema["minimum"].long()?.let { if (n < it) errors += "$path must be at least $it" }
                schema["maximum"].long()?.let { if (n > it) errors += "$path must be at most $it" }
            }
            "number" -> {
                val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.double()
                if (n == null) return run { errors += "$path must be a number" }
                schema["minimum"].double()?.let { if (n < it) errors += "$path must be at least $it" }
                schema["maximum"].double()?.let { if (n > it) errors += "$path must be at most $it" }
            }
            "boolean" -> if ((value as? JsonPrimitive)?.takeIf { !it.isString }?.bool() == null) errors += "$path must be true or false"
            "array" -> {
                val arr = value as? JsonArray ?: return run { errors += "$path must be an array" }
                schema["maxItems"].long()?.let { if (arr.size > it) errors += "$path must have at most $it items" }
                val items = schema["items"].obj()
                if (items != null) arr.forEachIndexed { i, el -> check(items, el, "$path[$i]", errors) }
            }
            else -> Unit
        }
    }
}

/** A few helpers so tool schemas read like what they declare. */
internal object Schema {
    fun obj(required: List<String> = emptyList(), vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    fun string(description: String, maxLength: Int = 400, minLength: Int? = null): JsonObject = buildJsonObject {
        put("type", "string"); put("description", description); put("maxLength", maxLength)
        if (minLength != null) put("minLength", minLength)
    }

    fun enum(description: String, vararg values: String): JsonObject = buildJsonObject {
        put("type", "string"); put("description", description)
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    fun integer(description: String, min: Int? = null, max: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer"); put("description", description)
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    }

    fun bool(description: String): JsonObject = buildJsonObject { put("type", "boolean"); put("description", description) }

    fun stringArray(description: String, maxItems: Int = 10, itemMax: Int = 200): JsonObject = buildJsonObject {
        put("type", "array"); put("description", description); put("maxItems", maxItems)
        put("items", string("item", itemMax))
    }
}
