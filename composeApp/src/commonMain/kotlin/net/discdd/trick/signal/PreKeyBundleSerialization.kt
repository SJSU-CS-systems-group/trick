package net.discdd.trick.signal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * JSON-serializable representation of a PreKeyBundle for trcky.org API.
 */
@Serializable
data class PreKeyBundleJson(
    val version: Int = 1,
    val registrationId: Int,
    val deviceId: Int,
    val preKeyId: Int? = null,
    val preKeyPublic: String? = null,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    val identityKey: String,
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
            version = 1,
            registrationId = bundle.registrationId,
            deviceId = bundle.deviceId,
            preKeyId = bundle.preKeyId,
            preKeyPublic = bundle.preKeyPublic?.let { Base64.encode(it) },
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKeyPublic = Base64.encode(bundle.signedPreKeyPublic),
            signedPreKeySignature = Base64.encode(bundle.signedPreKeySignature),
            identityKey = Base64.encode(bundle.identityKey),
            timestamp = timestamp
        )
        return json.encodeToString(jsonBundle)
    }

    /**
     * Deserialize JSON string to PreKeyBundleData.
     */
    fun deserialize(jsonString: String): PreKeyBundleData {
        val parsed = json.decodeFromString<PreKeyBundleJson>(jsonString)
        require(parsed.version == 1) { "Unsupported bundle version: ${parsed.version}" }

        return PreKeyBundleData(
            registrationId = parsed.registrationId,
            deviceId = parsed.deviceId,
            preKeyId = parsed.preKeyId,
            preKeyPublic = parsed.preKeyPublic?.let { Base64.decode(it) },
            signedPreKeyId = parsed.signedPreKeyId,
            signedPreKeyPublic = Base64.decode(parsed.signedPreKeyPublic),
            signedPreKeySignature = Base64.decode(parsed.signedPreKeySignature),
            identityKey = Base64.decode(parsed.identityKey)
        )
    }
}
