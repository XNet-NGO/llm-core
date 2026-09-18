package com.tddworks.openai.gateway.config

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

class AwsSigV4SignerTest {

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun hmac(key: ByteArray, data: ByteArray) = HmacSHA256(key).doFinal(data)

    /**
     * AWS-documented signing-key derivation vector.
     * https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#derive-signing-key
     * secret=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY, date=20150830, region=us-east-1, service=iam
     * Expected signing key (hex) is the AWS reference value.
     */
    @Test
    fun `derives the AWS reference signing key`() {
        val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        val kDate = hmac("AWS4$secret".encodeToByteArray(), "20150830".encodeToByteArray())
        val kRegion = hmac(kDate, "us-east-1".encodeToByteArray())
        val kService = hmac(kRegion, "iam".encodeToByteArray())
        val signingKey = hmac(kService, "aws4_request".encodeToByteArray())

        assertEquals(
            "2c94c0cf5378ada6887f09bb697df8fc0affdb34ba1cdd5bda32b664bd55b73c",
            signingKey.toHex(),
        )
    }

    @Test
    fun `signs deterministically and attaches required headers`() = runTest {
        AwsSigV4Signer.nowUtc = { "20150830T123600Z" to "20150830" }
        try {
            val creds =
                AwsSigV4Signer.sign(
                    ProviderAuth(
                        scheme = AuthScheme.SIGV4,
                        apiKey = "AKIDEXAMPLE",
                        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                        region = "us-east-1",
                        service = "bedrock",
                    ),
                    SigningContext(method = "POST", host = "bedrock.us-east-1.amazonaws.com", path = "/model/x/invoke"),
                )

            val auth = creds.headers["Authorization"]!!
            assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/bedrock/aws4_request"))
            assertTrue(auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
            // Exact signature verified against an independent reference (Python hmac) for this
            // canonical form — proves the canonical-request assembly is byte-for-byte correct.
            assertTrue(
                auth.endsWith("Signature=b6504014246d99078fbb32f25b22df4aebbe05e7910bf1e48014090bb02d7528"),
                "unexpected signature in: $auth",
            )
            assertEquals("20150830T123600Z", creds.headers["X-Amz-Date"])
            // Empty-body payload hash is the well-known SHA256 of "".
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                creds.headers["x-amz-content-sha256"],
            )

            // Deterministic: signing the same request again yields the same signature.
            val creds2 =
                AwsSigV4Signer.sign(
                    ProviderAuth(
                        scheme = AuthScheme.SIGV4,
                        apiKey = "AKIDEXAMPLE",
                        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                        region = "us-east-1",
                        service = "bedrock",
                    ),
                    SigningContext(method = "POST", host = "bedrock.us-east-1.amazonaws.com", path = "/model/x/invoke"),
                )
            assertEquals(auth, creds2.headers["Authorization"])
        } finally {
            AwsSigV4Signer.nowUtc = { defaultNowUtc() }
        }
    }

    @Test
    fun `includes session token header for temporary credentials`() = runTest {
        AwsSigV4Signer.nowUtc = { "20150830T123600Z" to "20150830" }
        try {
            val creds =
                AwsSigV4Signer.sign(
                    ProviderAuth(
                        scheme = AuthScheme.SIGV4,
                        apiKey = "AKIDEXAMPLE",
                        secretKey = "secret",
                        region = "us-east-1",
                        service = "bedrock",
                        sessionToken = "FQoGZ-token",
                    ),
                    SigningContext(method = "POST", host = "h", path = "/"),
                )
            assertEquals("FQoGZ-token", creds.headers["X-Amz-Security-Token"])
        } finally {
            AwsSigV4Signer.nowUtc = { defaultNowUtc() }
        }
    }

    @Test
    fun `returns empty credentials when keys are missing`() = runTest {
        val creds =
            AwsSigV4Signer.sign(
                ProviderAuth(scheme = AuthScheme.SIGV4, apiKey = "", secretKey = ""),
                SigningContext(method = "POST", host = "h", path = "/"),
            )
        assertTrue(creds.headers.isEmpty())
        assertNull(creds.headers["Authorization"])
    }

    @Test
    fun `formats amz date from epoch seconds`() {
        // 2015-08-30T12:36:00Z = 1440938160
        val (amzDate, dateStamp) = formatAmzDate(1_440_938_160)
        assertEquals("20150830T123600Z", amzDate)
        assertEquals("20150830", dateStamp)
    }
}
