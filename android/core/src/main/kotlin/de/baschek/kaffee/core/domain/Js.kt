package de.baschek.kaffee.core.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * The coffee app's data lives in plain JSON objects (JavaScript semantics). The domain port works on the same shape:
 * objects are [Obj] (insertion ordered), arrays are mutable lists, every number is a [Double] (like a JS number),
 * strings and booleans as usual. A key that is `undefined` in JavaScript is simply absent here, so a round trip
 * through JSON stays byte-for-byte the same shape as in the web app and on the server.
 */
typealias Obj = MutableMap<String, Any?>

fun obj(vararg pairs: Pair<String, Any?>): Obj = linkedMapOf(*pairs)

val KaffeeJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** Number.EPSILON */
const val JS_EPSILON: Double = 2.220446049250313E-16

/** round() of reducer.ts: Math.round((value + Number.EPSILON) * 100) / 100 with Math.round's half-up rule. */
fun jsRound(value: Double): Double {
    val scaled = (value + JS_EPSILON) * 100
    var whole = floor(scaled)
    if (scaled - whole >= 0.5) whole += 1
    return whole / 100
}

/** A number as a JavaScript template string prints it (12.3, 5 instead of 5.0). */
fun jsNum(value: Double): String =
    if (value == floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

fun JsonElement.toNative(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> linkedMapOf<String, Any?>().also { map -> forEach { (k, v) -> map[k] = v.toNative() } }
    is JsonArray -> mapTo(mutableListOf()) { it.toNative() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        else -> doubleOrNull ?: content
    }
}

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Double -> if (this == floor(this) && kotlin.math.abs(this) < 1e15) JsonPrimitive(toLong()) else JsonPrimitive(this)
    is Number -> JsonPrimitive(toDouble().let { if (it == floor(it)) it.toLong() else it })
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

fun parseObj(text: String): Obj = KaffeeJson.parseToJsonElement(text).toNative() as Obj

fun Any?.toJsonString(): String = KaffeeJson.encodeToString(JsonElement.serializer(), toJsonElement())

@Suppress("UNCHECKED_CAST")
fun <T> deepCopy(value: T): T = when (value) {
    is Map<*, *> -> linkedMapOf<String, Any?>().also { copy -> value.forEach { (k, v) -> copy[k as String] = deepCopy(v) } } as T
    is List<*> -> value.mapTo(mutableListOf()) { deepCopy(it) } as T
    else -> value
}

// Small typed accessors (absent keys behave like `undefined`).
@Suppress("UNCHECKED_CAST")
fun Obj.o(key: String): Obj = this[key] as Obj
@Suppress("UNCHECKED_CAST")
fun Obj.oOrNull(key: String): Obj? = this[key] as? Obj
@Suppress("UNCHECKED_CAST")
fun Obj.list(key: String): MutableList<Any?> = this[key] as MutableList<Any?>
@Suppress("UNCHECKED_CAST")
fun Obj.objs(key: String): List<Obj> = (this[key] as List<Any?>).map { it as Obj }
fun Obj.s(key: String): String = this[key] as String
fun Obj.sOrNull(key: String): String? = this[key] as? String
fun Obj.d(key: String): Double = (this[key] as Number).toDouble()
fun Obj.dOrNull(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Obj.b(key: String): Boolean = this[key] == true

/** Object.assign: a value of `null` means `undefined`, i.e. the key is removed. */
fun Obj.assign(vararg fields: Pair<String, Any?>) {
    for ((key, value) in fields) if (value == null) remove(key) else this[key] = value
}
