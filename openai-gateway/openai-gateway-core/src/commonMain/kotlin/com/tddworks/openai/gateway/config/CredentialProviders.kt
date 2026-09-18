package com.tddworks.openai.gateway.config

/**
 * Materialized credential contributions for a single request: headers and query params
 * to attach. Returned by a [RequestSigner]. Kept as plain data so the (platform-agnostic)
 * core never has to know *how* they were produced (HMAC signing, OAuth token exchange, …).
 */
data class SignedCredentials(
    val headers: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
)

/**
 * Context describing the request being signed. Minimal on purpose — a real AWS SigV4 signer
 * needs method/path/body, an OAuth2 token source needs none of it.
 */
data class SigningContext(
    val method: String,
    val host: String,
    val path: String,
    val body: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is SigningContext &&
                method == other.method &&
                host == other.host &&
                path == other.path &&
                body.contentEquals(other.body))

    override fun hashCode(): Int =
        (((method.hashCode() * 31) + host.hashCode()) * 31 + path.hashCode()) * 31 +
            body.contentHashCode()
}

/**
 * Host-pluggable request signer. `SIGV4` (AWS) and `OAUTH2` (Vertex/Entra) require platform
 * crypto or a live token exchange that a thin, platform-agnostic core intentionally does not
 * embed. The host registers an implementation via [CredentialProviders]; the config-driven
 * provider calls [sign] per request and attaches the returned headers/params.
 */
fun interface RequestSigner {
    suspend fun sign(auth: ProviderAuth, context: SigningContext): SignedCredentials
}

/**
 * Registry of [RequestSigner]s keyed by [AuthScheme]. The host installs signers at startup;
 * the core looks one up when it encounters a `SIGV4`/`OAUTH2` provider. When none is
 * registered, [resolve] returns null and the caller falls back to documented behavior
 * (see `applyAuthAndHeaders`), so an unconfigured signer degrades gracefully rather than
 * silently sending unsigned requests.
 */
object CredentialProviders {
    private val signers = mutableMapOf<AuthScheme, RequestSigner>()

    fun register(scheme: AuthScheme, signer: RequestSigner) {
        signers[scheme] = signer
    }

    fun unregister(scheme: AuthScheme) {
        signers.remove(scheme)
    }

    fun resolve(scheme: AuthScheme): RequestSigner? = signers[scheme]

    /** Test/reset helper. */
    fun clear() = signers.clear()
}
