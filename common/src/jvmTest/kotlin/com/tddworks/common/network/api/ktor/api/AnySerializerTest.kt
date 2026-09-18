package com.tddworks.common.network.api.ktor.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AnySerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun encode(value: Any): String = json.encodeToString(AnySerializer, value)

    private fun decode(text: String): Any = json.decodeFromString(AnySerializer, text)

    @Test
    fun `serializes primitives`() {
        assertEquals("\"hello\"", encode("hello"))
        assertEquals("42", encode(42))
        assertEquals("true", encode(true))
        assertEquals("false", encode(false))
        // Number path covers Double too
        assertTrue(encode(3.5).startsWith("3.5"))
    }

    @Test
    fun `serializes a nested map`() {
        val value = mapOf("a" to 1, "b" to mapOf("c" to "x"), "flag" to true)
        val element = json.parseToJsonElement(encode(value)).jsonObject
        assertEquals(1, element["a"]!!.jsonPrimitive.int)
        assertEquals("x", element["b"]!!.jsonObject["c"]!!.jsonPrimitive.content)
        assertTrue(element["flag"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `serializes a list and an array`() {
        val listElem = json.parseToJsonElement(encode(listOf(1, 2, 3))).jsonArray
        assertEquals(3, listElem.size)
        assertEquals(2, listElem[1].jsonPrimitive.int)

        val arrayElem = json.parseToJsonElement(encode(arrayOf("a", "b"))).jsonArray
        assertEquals("b", arrayElem[1].jsonPrimitive.content)
    }

    @Test
    fun `serializes non-standard types via toString`() {
        // The else branch stringifies unknown types.
        val encoded = encode(listOf('c'))
        assertEquals("[\"c\"]", encoded)
    }

    @Test
    fun `deserializes an object into a Map`() {
        @Suppress("UNCHECKED_CAST")
        val result = decode("""{"s":"txt","n":7,"d":1.5,"b":true}""") as Map<String, Any>
        assertEquals("txt", result["s"])
        assertEquals(7, result["n"])
        assertEquals(1.5, result["d"])
        assertEquals(true, result["b"])
    }

    @Test
    fun `deserializes a nested object and array`() {
        @Suppress("UNCHECKED_CAST")
        val result = decode("""{"list":[1,2],"obj":{"k":"v"}}""") as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val list = result["list"] as List<Any>
        assertEquals(listOf(1, 2), list)
        @Suppress("UNCHECKED_CAST")
        val obj = result["obj"] as Map<String, Any>
        assertEquals("v", obj["k"])
    }

    @Test
    fun `deserializes a top-level array`() {
        @Suppress("UNCHECKED_CAST")
        val result = decode("""["a",1,false]""") as List<Any>
        assertEquals("a", result[0])
        assertEquals(1, result[1])
        assertEquals(false, result[2])
    }

    @Test
    fun `round-trips a mixed structure`() {
        val original = mapOf("items" to listOf(1, 2), "meta" to mapOf("ok" to true))
        val decoded = decode(encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `distinguishes int from double on deserialize`() {
        @Suppress("UNCHECKED_CAST")
        val result = decode("""{"i":3,"f":3.0}""") as Map<String, Any>
        assertTrue(result["i"] is Int)
        assertFalse(result["f"] is Int)
    }
}
