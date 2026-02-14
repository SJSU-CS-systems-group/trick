package net.discdd.trick.metrics

/**
 * A single performance metric event.
 *
 * @property category  Grouping: "wifi_aware", "signal", "transport", "system", "stress_test"
 * @property name      Metric identifier, e.g. "encrypt_time", "discovery_time"
 * @property durationMs Measured duration in milliseconds (0 for non-duration metrics like sizes)
 * @property timestampMs Wall-clock time when the event was recorded (System.currentTimeMillis())
 * @property metadata  Extra key-value pairs, e.g. "peer_id", "plaintext_size", "content_type"
 */
data class MetricEvent(
    val category: String,
    val name: String,
    val durationMs: Double,
    val timestampMs: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
) {
    fun toCsvRow(): String {
        val metadataJson = if (metadata.isEmpty()) {
            "{}"
        } else {
            metadata.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":\"$v\""
            }
        }
        return "$timestampMs,$category,$name,${"%.3f".format(durationMs)},$metadataJson"
    }

    companion object {
        const val CSV_HEADER = "timestamp_ms,category,name,duration_ms,metadata_json"
    }
}

/**
 * Aggregated summary of a set of metric events.
 */
data class MetricSummary(
    val category: String,
    val name: String,
    val count: Int,
    val minMs: Double,
    val maxMs: Double,
    val avgMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val stdDevMs: Double
)

