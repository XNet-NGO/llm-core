package com.tddworks.openai.gateway.config

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CatalogCacheTest {

    private val models = listOf(CatalogModel(id = "m1"), CatalogModel(id = "m2"))

    @Test
    fun `get returns null on miss`() {
        val cache = CatalogCache(nowMs = { 0 })
        assertNull(cache.get("p"))
    }

    @Test
    fun `put then get returns entry within ttl`() {
        var now = 1000L
        val cache = CatalogCache(nowMs = { now })
        cache.put("p", models, ttlSeconds = 10)
        now = 5_000 // +4s, still within 10s ttl
        assertEquals(models, cache.get("p"))
    }

    @Test
    fun `entry expires after ttl`() {
        var now = 0L
        val cache = CatalogCache(nowMs = { now })
        cache.put("p", models, ttlSeconds = 10)
        now = 10_001 // just past 10s
        assertNull(cache.get("p"))
    }

    @Test
    fun `invalidate and clear remove entries`() {
        val cache = CatalogCache(nowMs = { 0 })
        cache.put("a", models, 100)
        cache.put("b", models, 100)
        cache.invalidate("a")
        assertNull(cache.get("a"))
        assertEquals(models, cache.get("b"))
        cache.clear()
        assertNull(cache.get("b"))
    }

    @Test
    fun `getOrFetch returns static models without fetching`() = runTest {
        val cache = CatalogCache(nowMs = { 0 })
        val config =
            ProviderConfig(
                id = "s",
                baseUrl = "https://x",
                catalog = Catalog(mode = CatalogMode.STATIC, models = models),
            )
        assertEquals(models, cache.getOrFetch(config))
        // STATIC must not populate the live cache.
        assertNull(cache.get("s"))
    }
}
