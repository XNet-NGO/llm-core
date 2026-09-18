package com.tddworks.openai.gateway.config

/**
 * Capability inference (§6.2, spec §5): derive a provider's effective [Capabilities] from its
 * live catalog (freeinference-style `input_modalities`/`output_modalities`/`supported_features`),
 * with **explicit config always winning** over inference.
 *
 * Inference rules (only ever turn a flag ON from catalog evidence; config can force either way):
 * - `image` in input modalities  → vision-capable chat (kept as `chat`, no separate flag)
 * - `image` in output modalities → `imagesGenerate`
 * - `audio` in output modalities → `tts`; `audio` in input modalities → `stt`
 * - `embedding`/`embed` feature or `embeddings` in outputs → `embeddings`
 * - `rerank` feature → `rerank`; `moderation` feature → `moderation`
 * - `reasoning`/`thinking` feature → (informational; no capability flag, surfaced via features)
 */
object CapabilityInference {

    fun infer(models: List<CatalogModel>): Capabilities {
        val inputs = models.flatMap { it.inputModalities }.map { it.lowercase() }.toSet()
        val outputs = models.flatMap { it.outputModalities }.map { it.lowercase() }.toSet()
        val features = models.flatMap { it.supportedFeatures }.map { it.lowercase() }.toSet()

        val embeddings =
            "embeddings" in outputs ||
                features.any { it == "embedding" || it == "embeddings" || it == "embed" }

        return Capabilities(
            chat = true,
            embeddings = embeddings,
            rerank = "rerank" in features,
            moderation = "moderation" in features,
            tts = "audio" in outputs || "tts" in features,
            stt = "audio" in inputs || "stt" in features,
            imagesGenerate = "image" in outputs || "images" in outputs,
        )
    }

    /**
     * Merge inferred capabilities under explicit config. A config flag that is `true` always
     * wins (explicit enable); inference can only add capabilities the config left at default
     * `false`. This matches spec §5: "explicit config always wins over catalog inference."
     */
    fun merge(config: Capabilities, inferred: Capabilities): Capabilities =
        Capabilities(
            chat = config.chat || inferred.chat,
            completions = config.completions,
            embeddings = config.embeddings || inferred.embeddings,
            responses = config.responses,
            rerank = config.rerank || inferred.rerank,
            moderation = config.moderation || inferred.moderation,
            tts = config.tts || inferred.tts,
            stt = config.stt || inferred.stt,
            imagesGenerate = config.imagesGenerate || inferred.imagesGenerate,
            imagesEdit = config.imagesEdit,
            voice = config.voice,
        )

    /** Convenience: infer from a resolved [Catalog] and merge under the provider's config. */
    fun resolve(config: ProviderConfig, catalog: Catalog): Capabilities =
        merge(config.capabilities, infer(catalog.models))
}
