package io.github.zcodeandroid.app.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Byte-exact implementation of the @zcode/shared RPC value serialization
 * (upstream packages/rpc/src/serialization.ts at the embedded UPSTREAM_PIN
 * 3c5b227649371507ddc4eb46ac5133afe6db1cab), mirrored from the same constants
 * the CI-proven register-workspace-project.cjs uses on-device.
 *
 * Format: [1 byte type tag] [VQL length/int] [data]
 *   Undefined=0 (no length, no data)
 *   String=1    (VQL len + UTF-8)
 *   Buffer=2    (VQL len + bytes)
 *   VSBuffer=3  (VQL len + bytes)
 *   Array=4     (VQL count + each element recursively tagged)
 *   Object=5    (VQL len + JSON.stringify UTF-8)  — JSON fallback
 *   Int=6       (VQL int)
 *
 * VQL: 7 data bits per byte, little-endian groups, MSB = continuation
 * (0 -> [0x00], 128 -> [0x80, 0x01]).
 *
 * NEGATIVE INTEGERS (TAG_INT) — signed 32-bit reinterpretation:
 * `writeVql` shifts with `ushr` and emits the unsigned 32-bit
 * representation of [value]; the reader accumulates the same bits and
 * reinterprets the accumulated Int sign. This matches the pinned upstream
 * TypeScript: JS `writeVQL` stores `value | 0` bit patterns and `readVQL`
 * returns the re-signed int, so e.g. -1 encodes to [0xFF... ] and decodes
 * back to -1, and lengths/counts (always non-negative upstream) are
 * unaffected. Upstream `(n|0) === n` already rejects non-int32 values,
 * routing them to the JSON fallback — negative int32 values were never
 * routed away, so the signed path is the documented upstream semantic.
 *
 * Fidelity notes (mirroring the TypeScript exactly):
 *  - Only the TOP-LEVEL value gets a tag; values nested inside an Object are
 *    pure JSON (numbers/booleans stay JSON there).
 *  - Array elements ARE tagged recursively.
 *  - JS decides Int via `typeof n === "number" && (n|0) === n`: Kotlin Int
 *    (and Long/Double with an exact int value in 32-bit range) -> Int tag.
 *    Everything else numeric -> Object tag with the JSON literal, matching
 *    JSON.stringify's numeric output.
 *  - Booleans and null as top-level values hit the JS else branch (Object tag
 *    + JSON literal). We mirror that.
 */
object Vql {
    const val TAG_UNDEFINED: Int = 0
    const val TAG_STRING: Int = 1
    const val TAG_BUFFER: Int = 2
    const val TAG_VSBUFFER: Int = 3
    const val TAG_ARRAY: Int = 4
    const val TAG_OBJECT: Int = 5
    const val TAG_INT: Int = 6

    /** Upstream marker for Uint8Array nested inside JSON objects. */
    private const val NESTED_U8_MARKER = "__zcode_rpc_nested_uint8array_v1"
    private const val NESTED_U8_B64_KEY = "base64"

    // ------------------------------------------------------------------ write

    fun writeVql(out: ByteArrayOutputStream, value: Int) {
        if (value == 0) {
            out.write(0)
            return
        }
        var v = value
        while (v != 0) {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        }
    }

    fun serialize(data: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        serializeInto(out, data)
        return out.toByteArray()
    }

    fun serializeInto(out: ByteArrayOutputStream, data: Any?) {
        when (data) {
            is RpcUndefined -> out.write(TAG_UNDEFINED)
            null -> out.write(TAG_UNDEFINED) // JSON null maps to wire undefined
            is String -> {
                out.write(TAG_STRING)
                val raw = data.toByteArray(Charsets.UTF_8)
                writeVql(out, raw.size)
                out.write(raw)
            }
            is ByteArray -> {
                out.write(TAG_BUFFER)
                writeVql(out, data.size)
                out.write(data)
            }
            is List<*> -> {
                out.write(TAG_ARRAY)
                writeVql(out, data.size)
                for (el in data) serializeInto(out, el)
            }
            is Int -> {
                out.write(TAG_INT)
                writeVql(out, data)
            }
            is Long -> {
                val asInt = data.toInt()
                if (asInt.toLong() == data) {
                    out.write(TAG_INT)
                    writeVql(out, asInt)
                } else {
                    // JS: (n|0)===n fails -> JSON fallback literal
                    writeObjectLiteral(out, data.toString())
                }
            }
            is Double -> {
                if (data.isNaN() || data.isInfinite()) {
                    // JSON.stringify maps NaN/Infinity to null
                    writeObjectLiteral(out, "null")
                } else if (data == data.toInt().toDouble()) {
                    out.write(TAG_INT)
                    writeVql(out, data.toInt())
                } else {
                    writeObjectLiteral(
                        out,
                        runCatching { JSONObject.numberToString(data) }.getOrDefault(data.toString()),
                    )
                }
            }
            is Map<*, *> -> writeObjectLiteral(out, mapToJson(data))
            is Boolean -> writeObjectLiteral(out, if (data) "true" else "false")
            else -> writeObjectLiteral(out, "null")
        }
    }

    private fun writeObjectLiteral(out: ByteArrayOutputStream, json: String) {
        out.write(TAG_OBJECT)
        val raw = json.toByteArray(Charsets.UTF_8)
        writeVql(out, raw.size)
        out.write(raw)
    }

    /** Nested ByteArray inside a Map becomes the upstream base64 marker object. */
    fun mapToJson(map: Map<*, *>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k.toString(), toJsonValue(v))
        }
        return obj.toString()
    }

    private fun toJsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is RpcUndefined -> JSONObject.NULL
        is Map<*, *> -> JSONObject(mapToJson(v))
        is List<*> -> {
            val arr = JSONArray()
            for (el in v) arr.put(toJsonValue(el))
            arr
        }
        is ByteArray -> nestedBufferMarker(v)
        else -> v
    }

    private fun nestedBufferMarker(bytes: ByteArray): JSONObject {
        val marker = JSONObject()
        marker.put(NESTED_U8_MARKER, true)
        marker.put(NESTED_U8_B64_KEY, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        return marker
    }

    // ------------------------------------------------------------------- read

    class Reader(private val buf: ByteArray) {
        var pos: Int = 0
            private set

        val remaining: Int get() = buf.size - pos

        private fun take(n: Int): ByteArray {
            require(pos + n <= buf.size) { "VQL read past end ($n at $pos of ${buf.size})" }
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun vql(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val b = take(1)[0].toInt() and 0xFF
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        fun value(): Any? {
            return when (val tag = take(1)[0].toInt() and 0xFF) {
                TAG_UNDEFINED -> null
                TAG_STRING -> String(take(vql()), Charsets.UTF_8)
                TAG_BUFFER, TAG_VSBUFFER -> take(vql())
                TAG_ARRAY -> {
                    val n = vql()
                    val out = ArrayList<Any?>(n.coerceAtMost(1024))
                    repeat(n) { out.add(value()) }
                    out
                }
                TAG_OBJECT -> Json.decodeNestedBuffers(
                    Json.toKotlin(String(take(vql()), Charsets.UTF_8))
                )
                TAG_INT -> vql()
                else -> throw IllegalArgumentException("unknown VQL tag $tag")
            }
        }
    }

    fun deserialize(bytes: ByteArray): Any? = Reader(bytes).value()
}

/** Explicit wire-undefined sentinel (distinct from JSON null). */
object RpcUndefined

/** Minimal org.json <-> Kotlin-native conversion shared by the RPC layer. */
internal object Json {
    fun toKotlin(json: String): Any? = when (json.trimStart().firstOrNull()) {
        '{' -> toKotlin(JSONObject(json))
        '[' -> toKotlin(JSONArray(json))
        // Route through toKotlin(v) so JSON null -> Kotlin null (opt() returns
        // the JSONObject.NULL sentinel, not null).
        else -> toKotlin(JSONObject("{\"v\":$json}").opt("v"))
    }

    fun toKotlin(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>(v.length())
            for (key in v.keys()) map[key] = toKotlin(v.opt(key))
            map
        }
        is JSONArray -> {
            val list = ArrayList<Any?>(v.length())
            for (i in 0 until v.length()) list.add(toKotlin(v.opt(i)))
            list
        }
        is Number -> normalizeNumber(v)
        else -> v
    }

    /**
     * org.json implementations disagree on number types: the Maven artifact
     * yields BigDecimal/BigInteger/Integer/Long, the Android framework yields
     * Integer/Long/Double. Upstream JS has ONE number type. Normalize decoded
     * numbers so the Kotlin side is identical on JVM tests and device:
     * integral -> Int (32-bit) or Long, fractional -> Double.
     */
    private fun normalizeNumber(n: Number): Any = when (n) {
        is Int, is Double -> n
        is Long -> if (n in Int.MIN_VALUE..Int.MAX_VALUE) n.toInt() else n
        is java.math.BigDecimal -> {
            val stripped = n.stripTrailingZeros()
            if (stripped.scale() <= 0) integralOf(stripped.toBigIntegerExact()) else n.toDouble()
        }
        is java.math.BigInteger -> integralOf(n)
        else -> n
    }

    private fun integralOf(bi: java.math.BigInteger): Any = when {
        bi.bitLength() <= 31 -> bi.toInt()
        bi.bitLength() <= 63 -> bi.toLong()
        else -> bi.toDouble() // beyond Long: JS would already be lossy
    }

    /** Decode the upstream nested-Uint8Array marker back to ByteArray (decodeRpcJsonValue). */
    fun decodeNestedBuffers(v: Any?): Any? = when (v) {
        is Map<*, *> -> {
            if (v[NESTED_U8_MARKER_KEY] == true && v.size == 2 && v[NESTED_U8_B64] is String) {
                runCatching {
                    android.util.Base64.decode(v[NESTED_U8_B64] as String, android.util.Base64.NO_WRAP)
                }.getOrDefault(v)
            } else {
                val out = LinkedHashMap<String, Any?>()
                for ((k, value) in v) out[k.toString()] = decodeNestedBuffers(value)
                out
            }
        }
        is List<*> -> v.map { decodeNestedBuffers(it) }
        else -> v
    }

    private const val NESTED_U8_MARKER_KEY = "__zcode_rpc_nested_uint8array_v1"
    private const val NESTED_U8_B64 = "base64"
}
