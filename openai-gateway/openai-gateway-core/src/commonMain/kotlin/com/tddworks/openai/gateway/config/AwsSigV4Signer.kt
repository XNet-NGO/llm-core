package com.tddworks.openai.gateway.config

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * AWS Signature Version 4 signer ([RequestSigner]) for `AuthScheme.SIGV4`, used by Bedrock
 * and other AWS services. Pure-Kotlin (KotlinCrypto) so it works on every KMP target.
 *
 * Credentials come from [ProviderAuth]: `apiKey` = access key id, `secretKey` = secret access
 * key, optional `sessionToken`, plus `region` and `service`. The signer produces the
 * `Authorization`, `X-Amz-Date`, `x-amz-content-sha256`, and (when present) `X-Amz-Security-Token`
 * headers for the request described by [SigningContext].
 *
 * Register once at startup:
 * ```
 * CredentialProviders.register(AuthScheme.SIGV4, AwsSigV4Signer)
 * ```
 *
 * Implements the canonical-request → string-to-sign → signing-key flow from
 * https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html.
 * The request timestamp is supplied via [nowUtc] (overridable for deterministic testing).
 */
object AwsSigV4Signer : RequestSigner {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"

    /** Returns the current UTC time as (yyyyMMddTHHmmssZ amzDate, yyyyMMdd dateStamp). */
    var nowUtc: () -> Pair<String, String> = { defaultNowUtc() }

    override suspend fun sign(auth: ProviderAuth, context: SigningContext): SignedCredentials {
        if (auth.apiKey.isEmpty() || auth.secretKey.isEmpty()) return SignedCredentials()

        val (amzDate, dateStamp) = nowUtc()
        val payloadHash = sha256Hex(context.body)

        // 1. Canonical request
        val canonicalUri = context.path.ifEmpty { "/" }
        val canonicalQuery = "" // query params are signed empty here; extend if signing query
        val canonicalHeaders =
            "host:${context.host}\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest =
            listOf(
                    context.method.uppercase(),
                    canonicalUri,
                    canonicalQuery,
                    canonicalHeaders,
                    signedHeaders,
                    payloadHash,
                )
                .joinToString("\n")

        // 2. String to sign
        val credentialScope = "$dateStamp/${auth.region}/${auth.service}/aws4_request"
        val stringToSign =
            listOf(
                    ALGORITHM,
                    amzDate,
                    credentialScope,
                    sha256Hex(canonicalRequest.encodeToByteArray()),
                )
                .joinToString("\n")

        // 3. Signing key + signature
        val signingKey = signatureKey(auth.secretKey, dateStamp, auth.region, auth.service)
        val signature = hmac(signingKey, stringToSign.encodeToByteArray()).toHex()

        val authorization =
            "$ALGORITHM Credential=${auth.apiKey}/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"

        val headers = buildMap {
            put("Authorization", authorization)
            put("X-Amz-Date", amzDate)
            put("x-amz-content-sha256", payloadHash)
            if (auth.sessionToken.isNotEmpty()) put("X-Amz-Security-Token", auth.sessionToken)
        }
        return SignedCredentials(headers = headers)
    }

    private fun signatureKey(
        secretKey: String,
        dateStamp: String,
        region: String,
        service: String,
    ): ByteArray {
        val kDate = hmac("AWS4$secretKey".encodeToByteArray(), dateStamp.encodeToByteArray())
        val kRegion = hmac(kDate, region.encodeToByteArray())
        val kService = hmac(kRegion, service.encodeToByteArray())
        return hmac(kService, "aws4_request".encodeToByteArray())
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = HmacSHA256(key).doFinal(data)

    private fun sha256Hex(data: ByteArray): String = SHA256().digest(data).toHex()

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Default UTC timestamp provider. Uses Ktor's multiplatform [io.ktor.util.date.GMTDate]
 * (available on every target the client runs on) so the signer needs no expect/actual.
 */
internal fun defaultNowUtc(): Pair<String, String> {
    val now = io.ktor.util.date.GMTDate()
    return formatAmzDate(now.timestamp / 1000)
}

/** Formats epoch seconds to (amzDate `yyyyMMddTHHmmssZ`, dateStamp `yyyyMMdd`) in UTC. */
internal fun formatAmzDate(epochSeconds: Long): Pair<String, String> {
    val days = epochSeconds / 86_400
    val secOfDay = (epochSeconds % 86_400).toInt()
    val hh = secOfDay / 3600
    val mm = (secOfDay % 3600) / 60
    val ss = secOfDay % 60

    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (if (m <= 2) y + 1 else y).toInt()

    fun p2(n: Int) = n.toString().padStart(2, '0')
    val dateStamp = "${year.toString().padStart(4, '0')}${p2(m)}${p2(d)}"
    val amzDate = "${dateStamp}T${p2(hh)}${p2(mm)}${p2(ss)}Z"
    return amzDate to dateStamp
}
