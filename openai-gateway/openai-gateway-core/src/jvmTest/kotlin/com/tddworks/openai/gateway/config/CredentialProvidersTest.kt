package com.tddworks.openai.gateway.config

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CredentialProvidersTest {

    @AfterEach fun tearDown() = CredentialProviders.clear()

    @Test
    fun `resolve returns null when nothing registered`() {
        assertNull(CredentialProviders.resolve(AuthScheme.SIGV4))
        assertNull(CredentialProviders.resolve(AuthScheme.OAUTH2))
    }

    @Test
    fun `register then resolve returns the signer`() = runTest {
        val signer = RequestSigner { _, _ -> SignedCredentials(headers = mapOf("X" to "1")) }
        CredentialProviders.register(AuthScheme.OAUTH2, signer)
        val resolved = CredentialProviders.resolve(AuthScheme.OAUTH2)
        assertEquals(signer, resolved)
        val creds = resolved!!.sign(ProviderAuth(scheme = AuthScheme.OAUTH2), SigningContext("GET", "h", "/"))
        assertEquals("1", creds.headers["X"])
    }

    @Test
    fun `unregister removes the signer`() {
        CredentialProviders.register(AuthScheme.SIGV4) { _, _ -> SignedCredentials() }
        CredentialProviders.unregister(AuthScheme.SIGV4)
        assertNull(CredentialProviders.resolve(AuthScheme.SIGV4))
    }

    @Test
    fun `clear removes all signers`() {
        CredentialProviders.register(AuthScheme.SIGV4) { _, _ -> SignedCredentials() }
        CredentialProviders.register(AuthScheme.OAUTH2) { _, _ -> SignedCredentials() }
        CredentialProviders.clear()
        assertNull(CredentialProviders.resolve(AuthScheme.SIGV4))
        assertNull(CredentialProviders.resolve(AuthScheme.OAUTH2))
    }

    @Test
    fun `register overwrites an existing signer for the same scheme`() {
        CredentialProviders.register(AuthScheme.SIGV4) { _, _ -> SignedCredentials(mapOf("v" to "1")) }
        val second = RequestSigner { _, _ -> SignedCredentials(mapOf("v" to "2")) }
        CredentialProviders.register(AuthScheme.SIGV4, second)
        assertEquals(second, CredentialProviders.resolve(AuthScheme.SIGV4))
    }

    @Test
    fun `SigningContext equality is content-based including body`() {
        val a = SigningContext("POST", "h", "/p", byteArrayOf(1, 2, 3))
        val b = SigningContext("POST", "h", "/p", byteArrayOf(1, 2, 3))
        val c = SigningContext("POST", "h", "/p", byteArrayOf(9))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assert(a != c)
    }
}
