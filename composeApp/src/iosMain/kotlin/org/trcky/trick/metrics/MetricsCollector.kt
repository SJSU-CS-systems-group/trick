package org.trcky.trick.metrics

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS implementation — no-op for now.
 * Metrics collection is Android-only for the research paper.
 */
actual object MetricsCollector {

    actual val isEnabled: Boolean
        get() = false

    actual fun recordDuration(
        category: String,
        name: String,
        durationMs: Double,
        metadata: Map<String, String>
    ) {
        // No-op on iOS
    }

    actual fun recordValue(
        category: String,
        name: String,
        metadata: Map<String, String>
    ) {
        // No-op on iOS
    }

    actual fun currentNanos(): Long {
        // Use NSDate for nanosecond-ish precision
        return (NSDate().timeIntervalSince1970 * 1_000_000_000).toLong()
    }
}

