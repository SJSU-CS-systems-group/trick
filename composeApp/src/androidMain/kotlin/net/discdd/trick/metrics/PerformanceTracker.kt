package net.discdd.trick.metrics

import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralised, thread-safe performance metrics collector.
 *
 * Usage:
 * - Direct recording:  `PerformanceTracker.record(MetricEvent(...))`
 * - Inline timing:     `val result = PerformanceTracker.measure("signal", "encrypt_time") { ... }`
 * - Split timing:      `val token = PerformanceTracker.startTimer("wifi_aware", "discovery_time")`
 *                       ... later ...
 *                       `PerformanceTracker.stopTimer(token, "wifi_aware", "discovery_time")`
 */
object PerformanceTracker {

    private const val TAG = "PerfMetrics"
    private const val MAX_EVENTS = 50_000

    /** Master switch — when false, all recording is a no-op. */
    val enabled = AtomicBoolean(true)

    // ── Device info ───────────────────────────────────────────────────────────
    private val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    private val deviceInfo: Map<String, String> = mapOf(
        "device_model" to deviceModel,
        "device_manufacturer" to Build.MANUFACTURER,
        "device_product" to Build.PRODUCT,
        "android_version" to Build.VERSION.RELEASE,
        "sdk_version" to Build.VERSION.SDK_INT.toString()
    )

    // ── Storage ──────────────────────────────────────────────────────────────
    private val events = ConcurrentLinkedQueue<MetricEvent>()
    private val eventCount = AtomicLong(0)

    // ── Split timers ─────────────────────────────────────────────────────────
    private val nextToken = AtomicLong(0)
    private val activeTimers = ConcurrentHashMap<Long, Long>() // token → startNanos

    // =====================================================================
    // Recording
    // =====================================================================

    /** Record a pre-built metric event. */
    fun record(event: MetricEvent) {
        if (!enabled.get()) return
        // Merge device info into metadata
        val eventWithDeviceInfo = event.copy(
            metadata = event.metadata + deviceInfo
        )
        enqueue(eventWithDeviceInfo)
        Log.d(TAG, "[${event.category}] ${event.name}: ${"%.3f".format(event.durationMs)}ms ${eventWithDeviceInfo.metadata}")
    }

    /**
     * Record a simple duration metric.
     */
    fun recordDuration(
        category: String,
        name: String,
        durationMs: Double,
        metadata: Map<String, String> = emptyMap()
    ) {
        record(MetricEvent(category, name, durationMs, metadata = metadata))
    }

    /**
     * Record a non-duration metric (e.g. sizes, counts).
     * Duration is set to 0; the value lives in metadata.
     */
    fun recordValue(
        category: String,
        name: String,
        metadata: Map<String, String>
    ) {
        record(MetricEvent(category, name, durationMs = 0.0, metadata = metadata))
    }

    // =====================================================================
    // Inline block timing
    // =====================================================================

    /**
     * Time a block of code and record the result.
     * Returns the block's return value so it can wrap any expression.
     */
    inline fun <T> measure(
        category: String,
        name: String,
        metadata: Map<String, String> = emptyMap(),
        block: () -> T
    ): T {
        if (!enabled.get()) return block()
        val startNanos = System.nanoTime()
        val result = block()
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000.0
        record(MetricEvent(category, name, durationMs, metadata = metadata))
        return result
    }

    // =====================================================================
    // Split (callback-spanning) timers
    // =====================================================================

    /**
     * Start a timer, returning a token.  Call [stopTimer] later with the same token.
     */
    fun startTimer(category: String, name: String): Long {
        if (!enabled.get()) return -1
        val token = nextToken.incrementAndGet()
        activeTimers[token] = System.nanoTime()
        Log.v(TAG, "Timer started: [$category] $name (token=$token)")
        return token
    }

    /**
     * Stop a previously started timer and record the elapsed duration.
     */
    fun stopTimer(
        token: Long,
        category: String,
        name: String,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (token < 0) return
        val startNanos = activeTimers.remove(token) ?: run {
            Log.w(TAG, "stopTimer called with unknown token $token for [$category] $name")
            return
        }
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000.0
        record(MetricEvent(category, name, durationMs, metadata = metadata))
    }

    /**
     * Cancel a timer without recording anything (e.g. on error paths).
     */
    fun cancelTimer(token: Long) {
        if (token < 0) return
        activeTimers.remove(token)
    }

    // =====================================================================
    // Memory snapshot helper
    // =====================================================================

    /** Record current heap usage + connection count. */
    fun recordMemorySnapshot(activeConnections: Int = 0) {
        if (!enabled.get()) return
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        recordValue("system", "memory_usage_bytes", mapOf(
            "bytes" to usedBytes.toString(),
            "total_bytes" to runtime.totalMemory().toString(),
            "connections" to activeConnections.toString()
        ))
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun enqueue(event: MetricEvent) {
        events.add(event)
        val count = eventCount.incrementAndGet()
        // FIFO eviction when over cap
        if (count > MAX_EVENTS) {
            events.poll()
            eventCount.decrementAndGet()
        }
    }
}

