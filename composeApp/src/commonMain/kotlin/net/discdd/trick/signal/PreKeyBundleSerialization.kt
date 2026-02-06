package net.discdd.trick.signal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * JSON-serializable representation of a PreKeyBundle for trcky.org API.
 *
 * Bundle Format Versioning (NOT Signal Protocol versions):
 * - v1: Classical EC prekeys only (deprecated - no longer supported)
 * - v2: Adds Kyber post-quantum prekeys (required by libsignal 0.86.7+)
 *
 * Note: Signal Protocol itself doesn't have "v1" and "v2" - it's a continuous evolution.
 * The requirement for Kyber is a security enhancement added to the protocol.
 */
@Serializable
data class PreKeyBundleJson(
    val version: Int = 2,
    val registrationId: Int,
    val deviceId: Int,
    val preKeyId: Int? = null,
    val preKeyPublic: String? = null,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    val identityKey: String,
    val kyberPreKeyId: Int? = null,
    val kyberPreKeyPublic: String? = null,
    val kyberPreKeySignature: String? = null,
    val timestamp: Long
)

/**
 * Serialization utilities for PreKeyBundleData.
 */
@OptIn(ExperimentalEncodingApi::class)
object PreKeyBundleSerialization {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Serialize PreKeyBundleData to JSON string for API upload.
     */
    fun serialize(bundle: PreKeyBundleData, timestamp: Long): String {
        val jsonBundle = PreKeyBundleJson(
            version = 2,
            registrationId = bundle.registrationId,
            deviceId = bundle.deviceId,
            preKeyId = bundle.preKeyId,
            preKeyPublic = bundle.preKeyPublic?.let { Base64.encode(it) },
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKeyPublic = Base64.encode(bundle.signedPreKeyPublic),
            signedPreKeySignature = Base64.encode(bundle.signedPreKeySignature),
            identityKey = Base64.encode(bundle.identityKey),
            kyberPreKeyId = bundle.kyberPreKeyId,
            kyberPreKeyPublic = bundle.kyberPreKeyPublic?.let { Base64.encode(it) },
            kyberPreKeySignature = bundle.kyberPreKeySignature?.let { Base64.encode(it) },
            timestamp = timestamp
        )
        return json.encodeToString(jsonBundle)
    }

    /**
     * Deserialize JSON string to PreKeyBundleData.
     *
     * Note: libsignal 0.86.7+ requires Kyber prekeys, so only v2 bundles are supported.
     * v1 bundles (without Kyber) are rejected.
     */
    fun deserialize(jsonString: String): PreKeyBundleData {
        val parsed = json.decodeFromString<PreKeyBundleJson>(jsonString)
        require(parsed.version == 2) {
            "Unsupported bundle version: ${parsed.version}. Only v2 bundles with Kyber prekeys are supported (libsignal 0.86.7+ requires Kyber)."
        }

        // Validate required Kyber fields
        require(parsed.kyberPreKeyId != null && parsed.kyberPreKeyId!! >= 0) {
            "Bundle missing required Kyber prekey ID. Only v2 bundles with Kyber support are accepted."
        }
        require(parsed.kyberPreKeyPublic != null && parsed.kyberPreKeyPublic!!.isNotEmpty()) {
            "Bundle missing required Kyber prekey public key. Only v2 bundles with Kyber support are accepted."
        }
        require(parsed.kyberPreKeySignature != null && parsed.kyberPreKeySignature!!.isNotEmpty()) {
            "Bundle missing required Kyber prekey signature. Only v2 bundles with Kyber support are accepted."
        }

        return PreKeyBundleData(
            registrationId = parsed.registrationId,
            deviceId = parsed.deviceId,
            preKeyId = parsed.preKeyId,
            preKeyPublic = parsed.preKeyPublic?.let { Base64.decode(it) },
            signedPreKeyId = parsed.signedPreKeyId,
            signedPreKeyPublic = Base64.decode(parsed.signedPreKeyPublic),
            signedPreKeySignature = Base64.decode(parsed.signedPreKeySignature),
            identityKey = Base64.decode(parsed.identityKey),
            kyberPreKeyId = parsed.kyberPreKeyId,
            kyberPreKeyPublic = parsed.kyberPreKeyPublic?.let { Base64.decode(it) },
            kyberPreKeySignature = parsed.kyberPreKeySignature?.let { Base64.decode(it) }
        )
    }
}
