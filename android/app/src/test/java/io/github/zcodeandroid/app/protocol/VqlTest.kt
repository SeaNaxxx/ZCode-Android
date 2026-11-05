package io.github.zcodeandroid.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * JVM unit tests for the restored 0.5.0 VQL codec (byte-exact mirror of
 * upstream packages/rpc/src/serialization.ts at the pinned revision).
 *
 * Scope notes:
 *  - Scalar/array/varint paths are byte-exact asserted.
 *  - Object literals are asserted STRUCTURALLY (round-trip content), not
 *    byte-exactly: on-device JSON key order is insertion-ordered (Android
 *    org.json), which is what the 0.5.0 corpus verified; the Maven
 *    org.json artifact used for JVM tests does not guarantee key order.
 *  - The nested-ByteArray base64 marker (android.util.Base64) is
 *    Android-framework only — covered on-device/instrumented, not here.
 */
class VqlTest {

    // ------------------------------------------------------------ varint (VQL)

    @Test
    fun `vql varint encodes known values`() {
        // ByteArray.toList() yields List<Byte> — map to unsigned Int so the
        // corpus compares like-for-like with List<Int> literals.
        fun enc(v: Int): List<Int> =
            ByteArrayOutputStream().also { Vql.writeVql(it, v) }.toByteArray()
                .map { it.toInt() and 0xFF }
        assertEquals(listOf(0x00), enc(0))
        assertEquals(listOf(0x01), enc(1))
        assertEquals(listOf(0x80, 0x01), enc(128))
        assertEquals(listOf(0xAC, 0x02), enc(300))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF, 0x0F), enc(-1))
    }

    @Test
    fun `vql varint reader round-trips`() {
        val values = listOf(0, 1, 127, 128, 300, 16384, Int.MAX_VALUE, -1, -100)
        for (v in values) {
            val out = ByteArrayOutputStream()
            Vql.writeVql(out, v)
            val r = Vql.Reader(out.toByteArray())
            assertEquals(v, r.vql())
            assertEquals(0, r.remaining)
        }
    }

    // ------------------------------------------------------------- scalars

    @Test
    fun `int round-trips via tag 6`() {
        val bytes = Vql.serialize(42)
        assertEquals(0x06, bytes[0].toInt() and 0xFF)
        assertEquals(42, Vql.deserialize(bytes))
    }

    @Test
    fun `string round-trips utf-8 including arabic`() {
        for (s in listOf("hello", "مرحبا", "mixed عربي 123", "", "line\nbreak")) {
            assertEquals(s, Vql.deserialize(Vql.serialize(s)))
        }
        val bytes = Vql.serialize("hello")
        assertEquals(0x01, bytes[0].toInt() and 0xFF)
        assertEquals(5, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `top-level buffer round-trips`() {
        val data = byteArrayOf(1, 2, 3, 0, -1)
        val back = Vql.deserialize(Vql.serialize(data))
        assertTrue(back is ByteArray)
        assertTrue(data.contentEquals(back as ByteArray))
    }

    @Test
    fun `null and undefined serialize to wire undefined`() {
        assertEquals(0x00, Vql.serialize(null)[0].toInt() and 0xFF)
        assertNull(Vql.deserialize(Vql.serialize(null)))
        assertNull(Vql.deserialize(Vql.serialize(RpcUndefined)))
    }

    @Test
    fun `boolean uses object-literal json fallback`() {
        val bytes = Vql.serialize(true)
        assertEquals(Vql.TAG_OBJECT, bytes[0].toInt() and 0xFF)
        assertEquals(true, Vql.deserialize(bytes))
        assertEquals(false, Vql.deserialize(Vql.serialize(false)))
    }

    @Test
    fun `integral double becomes int tag`() {
        assertEquals(3, Vql.deserialize(Vql.serialize(3.0)))
    }

    @Test
    fun `fractional double round-trips as json literal`() {
        assertEquals(1.5, Vql.deserialize(Vql.serialize(1.5)))
    }

    @Test
    fun `nan and infinity map to null like JSON stringify`() {
        assertNull(Vql.deserialize(Vql.serialize(Double.NaN)))
        assertNull(Vql.deserialize(Vql.serialize(Double.POSITIVE_INFINITY)))
    }

    // ------------------------------------------------------- arrays (recursive tags)

    @Test
    fun `array round-trips with recursive tagging`() {
        val list = listOf<Any?>(1, "two", null, listOf(3, 4), byteArrayOf(9, 9))
        val back = Vql.deserialize(Vql.serialize(list)) as List<*>
        assertEquals(5, back.size)
        assertEquals(1, back[0])
        assertEquals("two", back[1])
        assertNull(back[2])
        assertEquals(listOf(3, 4), back[3])
        assertTrue((back[4] as ByteArray).contentEquals(byteArrayOf(9, 9)))
        val bytes = Vql.serialize(list)
        assertEquals(Vql.TAG_ARRAY, bytes[0].toInt() and 0xFF)
    }

    @Test
    fun `empty array round-trips`() {
        assertEquals(emptyList<Any?>(), Vql.deserialize(Vql.serialize(emptyList<Any?>())))
    }

    // ------------------------------------------------------- objects (structural)

    @Test
    fun `map round-trips structurally`() {
        val map = mapOf("a" to 1, "b" to "x", "nested" to mapOf("deep" to listOf(1, 2)), "flag" to true)
        val back = Vql.deserialize(Vql.serialize(map)) as Map<*, *>
        assertEquals(4, back.size)
        assertEquals(1, back["a"])
        assertEquals("x", back["b"])
        assertEquals(mapOf("deep" to listOf(1, 2)), back["nested"])
        assertEquals(true, back["flag"])
    }

    @Test
    fun `map json fallback uses tag 5 with utf-8 length`() {
        val bytes = Vql.serialize(mapOf("k" to 1))
        assertEquals(Vql.TAG_OBJECT, bytes[0].toInt() and 0xFF)
        // single-key JSON string '{"k":1}' = 7 bytes; varint len then payload
        assertEquals(7, bytes[1].toInt() and 0xFF)
    }

    // ------------------------------------------------------------- errors

    @Test
    fun `unknown tag throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Vql.deserialize(byteArrayOf(0x7F))
        }
    }

    @Test
    fun `truncated payload throws`() {
        val full = Vql.serialize("hello")
        assertThrows(IllegalArgumentException::class.java) {
            Vql.deserialize(full.copyOfRange(0, full.size - 1))
        }
    }
}
