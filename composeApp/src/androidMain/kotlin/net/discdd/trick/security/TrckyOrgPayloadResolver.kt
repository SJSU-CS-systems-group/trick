package net.discdd.trick.security

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

/**
 * Android implementation of KeyExchangePayloadResolver using Ktor.
 * GET https://trcky.org/api/keys/{shortId} returns KeyExchangePayload JSON.
 * Returns null if the backend is unavailable or returns non-2xx.
 */
class TrckyOrgPayloadResolver(
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
    }
) : KeyExchangePayloadResolver {

    override suspend fun fetchPayloadByShortId(shortId: String): String? {
        return runCatching {
            val url = "$TRCKY_ORG_BASE_URL/api/keys/$shortId"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                response.bodyAsText()
            } else {
                null
            }
        }.getOrNull()
    }
}
