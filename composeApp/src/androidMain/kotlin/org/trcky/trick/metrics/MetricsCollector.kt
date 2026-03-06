package org.trcky.trick.metrics

/**
 * Android implementation — delegates to [PerformanceTracker].
 */
actual object MetricsCollector {

    actual val isEnabled: Boolean
        get() = PerformanceTracker.enabled.get()

    actual fun recordDuration(
        category: String,
        name: String,
        durationMs: Double,
        metadata: Map<String, String>
    ) {
        PerformanceTracker.recordDuration(category, name, durationMs, metadata)
    }

    actual fun recordValue(
        category: String,
        name: String,
        metadata: Map<String, String>
    ) {
        PerformanceTracker.recordValue(category, name, metadata)
    }

    actual fun currentNanos(): Long = System.nanoTime()
}

