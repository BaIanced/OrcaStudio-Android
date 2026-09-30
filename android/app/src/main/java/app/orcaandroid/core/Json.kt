package app.orcaandroid.core

import org.json.JSONArray
import org.json.JSONObject

class OrcaException(message: String) : Exception(message)

internal fun JSONObject.throwIfError(): JSONObject {
    if (has("error")) throw OrcaException(getString("error"))
    return this
}

internal inline fun <T> JSONArray.map(transform: (Any) -> T): List<T> = List(length()) { transform(get(it)) }

internal fun JSONArray?.floats(): FloatArray = if (this == null) FloatArray(0) else FloatArray(length()) { getDouble(it).toFloat() }

internal fun JSONObject.vec3(key: String): Vec3 = optJSONArray(key).floats().let { if (it.size >= 3) Vec3(it[0], it[1], it[2]) else Vec3.ZERO }

internal fun JSONObject.stringMap(key: String): Map<String, String> {
    val o = optJSONObject(key) ?: return emptyMap()
    return o.keys().asSequence().associateWith { o.get(it).toString() }
}

internal fun JSONObject?.strings(): Map<String, String> =
    if (this == null) emptyMap() else keys().asSequence().associateWith { get(it).toString() }

internal fun Map<String, Any?>.toJson(): JSONObject = JSONObject().also { o ->
    forEach { (k, v) ->
        o.put(k, when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (v as Map<String, Any?>).toJson()
            is Collection<*> -> JSONArray(v.map { if (it is Map<*, *>) @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>).toJson() else it })
            is Vec3 -> JSONArray(listOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble()))
            is FloatArray -> JSONArray(v.map { it.toDouble() })
            else -> v
        })
    }
}

fun args(vararg pairs: Pair<String, Any?>): JSONObject = mapOf(*pairs).toJson()
