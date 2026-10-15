package local.signaldock.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import local.signaldock.ingestion.PlanProperty
import local.signaldock.ingestion.PlanValueType
import local.signaldock.ingestion.TrackingPlan
import java.io.File
import java.io.IOException

/** Value types of `tracking-plan/plan.json` (the same four as `generate.mjs`). */
private val PLAN_TYPES = mapOf(
    "string" to PlanValueType.STRING,
    "integer" to PlanValueType.INTEGER,
    "number" to PlanValueType.NUMBER,
    "boolean" to PlanValueType.BOOLEAN,
)

/**
 * Reads the plan the server marks events against (ADR-0022). A plan that cannot be read or does not
 * have the structure below throws [IllegalArgumentException] with every problem, so startup fails
 * with one clear error. Naming style, descriptions, and the Kotlin package stay the job of
 * `tracking-plan/generate.mjs`; the server needs only unambiguous names, keys, types, and required flags.
 */
internal fun loadTrackingPlan(path: String): TrackingPlan {
    val text = try {
        File(path).readText()
    } catch (error: IOException) {
        throw IllegalArgumentException("SIGNALDOCK_TRACKING_PLAN: cannot read $path: ${error.message}")
    }
    return parseTrackingPlan(text, path)
}

internal fun parseTrackingPlan(text: String, source: String = "tracking plan"): TrackingPlan {
    val root = try {
        Json.parseToJsonElement(text)
    } catch (error: IllegalArgumentException) { // SerializationException is one
        throw IllegalArgumentException("SIGNALDOCK_TRACKING_PLAN: $source is not valid JSON: ${error.message}")
    }
    val errors = mutableListOf<String>()
    val events = mutableMapOf<String, List<PlanProperty>>()
    val obj = root as? JsonObject
    if ((obj?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.content != "1") errors += "version must be 1"
    val list = obj?.get("events") as? JsonArray
    if (list == null || list.isEmpty()) errors += "events must be a non-empty array"
    list?.forEachIndexed { index, element ->
        val event = element as? JsonObject
        val name = text(event?.get("name"))
        if (name == null) { errors += "events[$index]: name must be a non-empty string"; return@forEachIndexed }
        if (name in events) errors += "event \"$name\": duplicate event name"
        val props = event?.get("properties") as? JsonArray
        if (props == null) { errors += "event \"$name\": properties must be an array"; return@forEachIndexed }
        val parsed = mutableListOf<PlanProperty>()
        props.forEach { element2 ->
            val prop = element2 as? JsonObject
            val key = text(prop?.get("key"))
            val type = text(prop?.get("type"))?.let { PLAN_TYPES[it] }
            val required = (prop?.get("required") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            val at = "event \"$name\", property \"${key ?: "?"}\""
            if (key == null) errors += "event \"$name\": property key must be a non-empty string"
            else if (parsed.any { it.key == key }) errors += "$at: duplicate key"
            if (type == null) errors += "$at: type must be one of ${PLAN_TYPES.keys.joinToString(", ")}"
            if (required == null) errors += "$at: required must be true or false"
            if (key != null && type != null && required != null) parsed += PlanProperty(key, type, required)
        }
        events[name] = parsed
    }
    require(errors.isEmpty()) { "SIGNALDOCK_TRACKING_PLAN: invalid tracking plan $source:\n- " + errors.joinToString("\n- ") }
    return TrackingPlan(events)
}

private fun text(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
