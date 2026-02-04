package net.discdd.trick.signal

/**
 * Interface for fetching and uploading PreKey bundles to trcky.org.
 */
interface PreKeyBundleResolver {
    /**
     * Fetch bundle by shortId.
     * @throws SignalError.BundleFetchFailed on 404 or network error
     */
    suspend fun fetchBundleByShortId(shortId: String): PreKeyBundleData

    /**
     * Upload bundle to trcky.org.
     * @return true if success
     */
    suspend fun uploadBundle(shortId: String, bundle: PreKeyBundleData): Boolean
}
