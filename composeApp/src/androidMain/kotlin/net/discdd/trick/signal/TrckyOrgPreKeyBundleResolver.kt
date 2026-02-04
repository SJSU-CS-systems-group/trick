package net.discdd.trick.signal

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import net.discdd.trick.security.TRCKY_ORG_BASE_URL

/**
 * Android implementation of PreKeyBundleResolver using Ktor.
 *
 * Endpoints:
 * - GET  /api/bundles/{shortId} - Fetch bundle (404 if none)
 * - POST /api/bundles/{shortId} - Upload/update bundle
 */
class TrckyOrgPreKeyBundleResolver(
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
    }
) : PreKeyBundleResolver {

    companion object {
        private const val TAG = "TrckyOrgPreKeyBundleResolver"
    }

    override suspend fun fetchBundleByShortId(shortId: String): PreKeyBundleData {
        return try {
            val url = "$TRCKY_ORG_BASE_URL/api/bundles/$shortId"
            Log.d(TAG, "Fetching bundle from: $url")

            val response = httpClient.get(url)
            when (response.status) {
                HttpStatusCode.OK -> {
                    val json = response.bodyAsText()
                    Log.d(TAG, "Successfully fetched bundle for $shortId")
                    PreKeyBundleSerialization.deserialize(json)
                }
                HttpStatusCode.NotFound -> {
                    Log.w(TAG, "Bundle not found for $shortId")
                    throw SignalError.BundleFetchFailed(
                        shortId,
                        "Bundle not found - contact may need to refresh QR"
                    )
                }
                else -> {
                    Log.e(TAG, "HTTP error ${response.status} fetching bundle for $shortId")
                    throw SignalError.BundleFetchFailed(shortId, "HTTP ${response.status}")
                }
            }
        } catch (e: SignalError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Network error fetching bundle for $shortId: ${e.message}")
            throw SignalError.BundleFetchFailed(shortId, e.message ?: "Network error")
        }
    }

    override suspend fun uploadBundle(shortId: String, bundle: PreKeyBundleData): Boolean {
        return try {
            val url = "$TRCKY_ORG_BASE_URL/api/bundles/$shortId"
            val json = PreKeyBundleSerialization.serialize(bundle, System.currentTimeMillis())

            Log.d(TAG, "Uploading bundle to: $url")

            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(json)
            }

            val success = response.status == HttpStatusCode.OK ||
                          response.status == HttpStatusCode.Created

            if (success) {
                Log.d(TAG, "Successfully uploaded bundle for $shortId")
            } else {
                Log.e(TAG, "Failed to upload bundle for $shortId: HTTP ${response.status}")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Bundle upload failed for $shortId: ${e.message}")
            false
        }
    }
}
