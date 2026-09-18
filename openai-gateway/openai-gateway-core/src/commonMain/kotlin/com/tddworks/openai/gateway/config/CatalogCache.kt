package com.tddworks.openai.gateway.config

import io.ktor.util.date.GMTDate

/**
 * Host-side TTL cache for live model catalogs (§6.1). Keyed by provider id; entries expire
 * after `catalog.ttlSeconds`. Multiplatform (Ktor `GMTDate` clock); the clock is injectable
 * for deterministic testing.
 *
 * This is a thin, in-memory cache that lives with the SDK so callers do not each re-implement
 * TTL bookkeeping. A gateway process can share one instance across requests. It never fetches
 * on its own — [getOrFetch] delegates to [ProviderCatalogLoader.fetchModels] on a miss/expiry.
 */
class CatalogCache(private val nowMs: () -> Long = { GMTDate().timestamp }) {

    private data class Entry(val models: List<CatalogModel>, val expiresAtMs: Long)

    private val entries = mutableMapOf<String, Entry>()

    /** Currently-cached, non-expired entries for [providerId], or null on miss/expiry. */
    fun get(providerId: String): List<CatalogModel>? {
        val e = entries[providerId] ?: return null
        if (nowMs() >= e.expiresAtMs) {
            entries.remove(providerId)
            return null
        }
        return e.models
    }

    /** Store [models] for [providerId] with a TTL of [ttlSeconds]. */
    fun put(providerId: String, models: List<CatalogModel>, ttlSeconds: Long) {
        entries[providerId] = Entry(models, nowMs() + ttlSeconds * 1000)
    }

    fun invalidate(providerId: String) {
        entries.remove(providerId)
    }

    fun clear() = entries.clear()

    /**
     * Return cached models for [config], fetching + caching on miss/expiry. STATIC catalogs are
     * never fetched (returns the configured list). Honors `catalog.ttlSeconds`.
     */
    suspend fun getOrFetch(config: ProviderConfig): List<CatalogModel> {
        if (config.catalog.mode == CatalogMode.STATIC) return config.catalog.models
        get(config.id)?.let {
            return it
        }
        val fetched = ProviderCatalogLoader.fetchModels(config)
        put(config.id, fetched, config.catalog.ttlSeconds)
        return fetched
    }

    /** Resolve the effective [Catalog] using the cache as the live source. */
    suspend fun resolveCatalog(config: ProviderConfig): Catalog =
        ProviderCatalogLoader.resolveCatalog(
            config,
            cached = if (config.catalog.mode == CatalogMode.STATIC) null else getOrFetch(config),
        )
}
