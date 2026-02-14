package net.discdd.trick.metrics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Centralised, thread-safe performance metrics collector.
 *
 * Usage:
 * - Direct recording:  `PerformanceTracker.record(MetricEvent(...))`
 * - Inline timing:     `val result = PerformanceTracker.measure("signal", "encrypt_time") { ... }`
 * - Split timing:      `val token = PerformanceTracker.startTimer("wifi_aware", "discovery_time")`
 *                       ... later ...
 *                       `PerformanceTracker.stopTimer(token, "wifi_aware", "discovery_time")`
 * - Export:            `PerformanceTracker.exportCsv(context)`
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

    // =====================================================================
    // Querying
    // =====================================================================

    /** Return all events, optionally filtered by category and/or name. */
    fun getEvents(category: String? = null, name: String? = null): List<MetricEvent> {
        return events.filter { e ->
            (category == null || e.category == category) &&
            (name == null || e.name == name)
        }
    }

    /** Compute summary statistics for a specific metric. */
    fun getSummary(category: String, name: String): MetricSummary? {
        val matching = getEvents(category, name)
        if (matching.isEmpty()) return null
        val durations = matching.map { it.durationMs }.sorted()
        val count = durations.size
        val avg = durations.average()
        val variance = durations.map { (it - avg) * (it - avg) }.average()
        return MetricSummary(
            category = category,
            name = name,
            count = count,
            minMs = durations.first(),
            maxMs = durations.last(),
            avgMs = avg,
            p50Ms = durations[count / 2],
            p95Ms = durations[(count * 0.95).toInt().coerceAtMost(count - 1)],
            stdDevMs = sqrt(variance)
        )
    }

    /** Get summaries for all distinct (category, name) pairs. */
    fun getAllSummaries(): List<MetricSummary> {
        val pairs = events.map { it.category to it.name }.distinct()
        return pairs.mapNotNull { (cat, name) -> getSummary(cat, name) }
    }

    // =====================================================================
    // Export
    // =====================================================================

    /**
     * Export all events to a CSV file in the app's external files directory.
     * Falls back to internal storage if external storage is unavailable.
     * Returns the written [File], or null on failure.
     */
    fun exportCsv(context: Context): File? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "trick_metrics_$timestamp.csv"
            
            // Try external storage first
            var dir = context.getExternalFilesDir(null)
            var file: File? = null
            
            if (dir != null) {
                try {
                    file = File(dir, filename)
                    file.bufferedWriter().use { writer ->
                        writer.appendLine(MetricEvent.CSV_HEADER)
                        events.forEach { event ->
                            writer.appendLine(event.toCsvRow())
                        }
                    }
                    Log.i(TAG, "Exported ${events.size} events to external storage: ${file.absolutePath}")
                    return file
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write to external storage: ${e.message}, trying internal storage...")
                }
            } else {
                Log.w(TAG, "External files directory is null, trying internal storage...")
            }
            
            // Fallback to internal storage
            dir = context.filesDir
            file = File(dir, filename)
            file.bufferedWriter().use { writer ->
                writer.appendLine(MetricEvent.CSV_HEADER)
                events.forEach { event ->
                    writer.appendLine(event.toCsvRow())
                }
            }
            Log.i(TAG, "Exported ${events.size} events to internal storage: ${file.absolutePath}")
            Log.i(TAG, "Pull with: adb pull ${file.absolutePath}")
            Log.i(TAG, "Or use: adb shell run-as net.discdd.trick cat ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "CSV export failed: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * Export all events as a single CSV string (useful for sharing via intent or logging).
     */
    fun exportCsvString(): String {
        val sb = StringBuilder()
        sb.appendLine(MetricEvent.CSV_HEADER)
        events.forEach { sb.appendLine(it.toCsvRow()) }
        return sb.toString()
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    /** Clear all recorded events and active timers. */
    fun clear() {
        events.clear()
        eventCount.set(0)
        activeTimers.clear()
        Log.i(TAG, "All metrics cleared")
    }

    /** Total number of events currently stored. */
    fun size(): Int = events.size

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

