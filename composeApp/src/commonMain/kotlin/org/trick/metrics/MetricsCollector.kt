package org.trick.metrics

/**
 * Platform-agnostic metrics collection interface.
 *
 * Android `actual` delegates to [PerformanceTracker].
 * iOS `actual` is a no-op (metrics collection is Android-only for now).
 *
 * Use this from commonMain code (e.g. SignalSessionManager) to record
 * performance metrics without depending on Android APIs.
 */
expect object MetricsCollector {

    /** Whether metrics collection is currently active. */
    val isEnabled: Boolean

    /**
     * Record a duration metric.
     *
     * @param category Grouping: "wifi_aware", "signal", "transport", "system"
     * @param name     Metric identifier, e.g. "encrypt_time"
     * @param durationMs Elapsed time in milliseconds
     * @param metadata Extra key-value data (e.g. "plaintext_size" → "256")
     */
    fun recordDuration(
        category: String,
        name: String,
        durationMs: Double,
        metadata: Map<String, String>
    )

    /**
     * Record a non-duration value metric (e.g. sizes, counts).
     */
    fun recordValue(
        category: String,
        name: String,
        metadata: Map<String, String>
    )

    /**
     * Return the current high-resolution time in nanoseconds.
     * Used by callers to compute durations across suspend boundaries.
     */
    fun currentNanos(): Long
}

