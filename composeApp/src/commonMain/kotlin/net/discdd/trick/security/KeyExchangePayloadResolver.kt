package net.discdd.trick.security

/**
 * Resolves a shortId to the full KeyExchangePayload JSON from trcky.org.
 * Used when the user scans a trcky.org URL instead of the full QR payload.
 *
 * Expected API: GET https://trcky.org/api/keys/{shortId} returns KeyExchangePayload JSON.
 * If the backend is not deployed, implementations may return null.
 */
interface KeyExchangePayloadResolver {
    /**
     * Fetch the key exchange payload JSON for the given shortId.
     * @return The payload JSON string, or null if not found or on error.
     */
    suspend fun fetchPayloadByShortId(shortId: String): String?
}
