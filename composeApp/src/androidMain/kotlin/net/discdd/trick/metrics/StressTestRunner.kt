package net.discdd.trick.metrics

import android.util.Log
import kotlinx.coroutines.*
import net.discdd.trick.screens.messaging.AndroidWifiAwareManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Programmatic stress-test runner for measuring throughput and scalability.
 *
 * Generates synthetic messages at controlled rates and records metrics
 * through the existing [PerformanceTracker] infrastructure.
 *
 * Three modes:
 * - **burst**: send N messages as fast as possible
 * - **ramp**: gradually increase send rate from 1 to N msg/s
 * - **benchmark**: send messages of varying sizes through the full encrypted E2E pipeline
 *                  with pacing so each message completes fully (populates Table 2 metrics)
 */
class StressTestRunner(
    private val wifiAwareManager: AndroidWifiAwareManager
) {
    private val TAG = "PerfStressTest"
    private var runningJob: Job? = null

    /**
     * Run a burst test: send [textCount] text messages and [imageCount] images
     * as fast as possible to [peerId].
     * 
     * @param bidirectional If true, enables auto-reply on the receiving device so the session ratchets
     *                      and subsequent messages use SignalMessage (type 1) instead of PreKeySignalMessage (type 3).
     */
    fun runBurst(
        peerId: String,
        textCount: Int = 100,
        imageCount: Int = 0,
        imageSizeKb: Int = 100,
        bidirectional: Boolean = true,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) {
        if (runningJob?.isActive == true) {
            Log.w(TAG, "Stress test already running, ignoring")
            return
        }

        Log.i(TAG, "Starting BURST test: $textCount texts, $imageCount images ($imageSizeKb KB each) to ${peerId.take(8)} (bidirectional=$bidirectional)")
        
        // Enable bidirectional mode if requested
        if (bidirectional) {
            wifiAwareManager.setBidirectionalStressTestMode(peerId)
        }
        
        PerformanceTracker.record(MetricEvent("stress_test", "burst_start", 0.0,
            metadata = mapOf(
                "peer_id" to peerId.take(8),
                "text_count" to textCount.toString(),
                "image_count" to imageCount.toString(),
                "image_size_kb" to imageSizeKb.toString(),
                "bidirectional" to bidirectional.toString()
            )))

        val startTime = System.nanoTime()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        runningJob = scope.launch {
            // ── Text burst ───────────────────────────────────────────
            for (i in 1..textCount) {
                if (!isActive) break
                try {
                    wifiAwareManager.sendMessageToPeer(
                        "stress_test_msg_$i/${textCount}_${System.currentTimeMillis()}",
                        peerId
                    )
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                    Log.w(TAG, "Text send failed at $i: ${e.message}")
                }
                // Small delay so the async sendEncryptedContent coroutine can actually execute
                delay(5)
            }

            // ── Image burst ──────────────────────────────────────────
            if (imageCount > 0) {
                val imageBytes = generateSyntheticImage(imageSizeKb * 1024)
                for (i in 1..imageCount) {
                    if (!isActive) break
                    try {
                        wifiAwareManager.sendPictureToPeer(
                            imageBytes,
                            "stress_test_img_$i.jpg",
                            "image/jpeg",
                            peerId
                        )
                        successCount.incrementAndGet()
                    } catch (e: Exception) {
                        failCount.incrementAndGet()
                        Log.w(TAG, "Image send failed at $i: ${e.message}")
                    }
                    delay(50)  // Larger delay for images
                }
            }

            // Wait a bit for the last async sends to finish
            delay(2000)

            val totalMs = (System.nanoTime() - startTime) / 1_000_000.0
            val totalMessages = textCount + imageCount
            val throughput = if (totalMs > 0) (successCount.get() * 1000.0 / totalMs) else 0.0

            PerformanceTracker.record(MetricEvent("stress_test", "burst_end", totalMs,
                metadata = mapOf(
                    "peer_id" to peerId.take(8),
                    "total_messages" to totalMessages.toString(),
                    "success_count" to successCount.get().toString(),
                    "fail_count" to failCount.get().toString(),
                    "throughput_msg_per_sec" to "%.2f".format(throughput)
                )))

            PerformanceTracker.recordMemorySnapshot()
            
            // Disable bidirectional mode
            if (bidirectional) {
                wifiAwareManager.setBidirectionalStressTestMode(null)
            }
            
            Log.i(TAG, "BURST complete: ${successCount.get()}/$totalMessages sent in ${totalMs}ms " +
                "(throughput: ${"%.2f".format(throughput)} msg/s, failures: ${failCount.get()})")
        }
    }

    /**
     * Run a ramp test: gradually increase send rate from 1 msg/sec to [maxRate] msg/sec,
     * spending [stepDurationSec] seconds at each rate level.
     * 
     * @param bidirectional If true, enables auto-reply on the receiving device so the session ratchets.
     */
    fun runRamp(
        peerId: String,
        maxRate: Int = 50,
        stepDurationSec: Int = 5,
        bidirectional: Boolean = true,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) {
        if (runningJob?.isActive == true) {
            Log.w(TAG, "Stress test already running, ignoring")
            return
        }

        Log.i(TAG, "Starting RAMP test: 1 to $maxRate msg/sec, $stepDurationSec sec/step to ${peerId.take(8)} (bidirectional=$bidirectional)")
        
        // Enable bidirectional mode if requested
        if (bidirectional) {
            wifiAwareManager.setBidirectionalStressTestMode(peerId)
        }
        
        PerformanceTracker.record(MetricEvent("stress_test", "ramp_start", 0.0,
            metadata = mapOf(
                "peer_id" to peerId.take(8),
                "max_rate" to maxRate.toString(),
                "step_duration_sec" to stepDurationSec.toString(),
                "bidirectional" to bidirectional.toString()
            )))

        val overallStart = System.nanoTime()

        runningJob = scope.launch {
            for (rate in 1..maxRate) {
                if (!isActive) break

                val intervalMs = 1000L / rate
                val successCount = AtomicInteger(0)
                val failCount = AtomicInteger(0)
                val stepStart = System.nanoTime()
                val stepEndTarget = stepStart + (stepDurationSec * 1_000_000_000L)
                var msgIndex = 0

                while (System.nanoTime() < stepEndTarget && isActive) {
                    msgIndex++
                    val sendStart = System.nanoTime()
                    try {
                        wifiAwareManager.sendMessageToPeer(
                            "ramp_r${rate}_m${msgIndex}_${System.currentTimeMillis()}",
                            peerId
                        )
                        successCount.incrementAndGet()
                    } catch (e: Exception) {
                        failCount.incrementAndGet()
                    }

                    // Pace to target rate
                    val elapsed = (System.nanoTime() - sendStart) / 1_000_000
                    val sleepMs = intervalMs - elapsed
                    if (sleepMs > 0) delay(sleepMs)
                }

                val stepMs = (System.nanoTime() - stepStart) / 1_000_000.0
                val achieved = if (stepMs > 0) (successCount.get() * 1000.0 / stepMs) else 0.0

                PerformanceTracker.record(MetricEvent("stress_test", "ramp_step", stepMs,
                    metadata = mapOf(
                        "peer_id" to peerId.take(8),
                        "target_rate" to rate.toString(),
                        "achieved_rate" to "%.2f".format(achieved),
                        "success" to successCount.get().toString(),
                        "fail" to failCount.get().toString()
                    )))

                PerformanceTracker.recordMemorySnapshot()

                Log.i(TAG, "RAMP step: target=$rate msg/s, achieved=${"%.2f".format(achieved)} msg/s, " +
                    "success=${successCount.get()}, fail=${failCount.get()}")

                // If too many failures at this rate, stop ramping
                if (failCount.get() > successCount.get() && rate > 1) {
                    Log.w(TAG, "Failure rate > 50% at $rate msg/s — stopping ramp")
                    break
                }
            }

            val totalMs = (System.nanoTime() - overallStart) / 1_000_000.0
            PerformanceTracker.record(MetricEvent("stress_test", "ramp_end", totalMs,
                metadata = mapOf("peer_id" to peerId.take(8))))
            
            // Disable bidirectional mode
            if (bidirectional) {
                wifiAwareManager.setBidirectionalStressTestMode(null)
            }

            Log.i(TAG, "RAMP complete in ${totalMs}ms")
        }
    }

    /**
     * Run a benchmark: send messages of varying sizes through the FULL encrypted E2E pipeline.
     * Unlike burst/ramp, this paces messages with delays (500ms each) so every message
     * fully completes the encrypt → serialize → socket write → receive pipeline.
     *
     * This populates Table 2 metrics (E2E latency, encrypt/decrypt time, ciphertext overhead)
     * with data across different message sizes.
     *
     * Size categories:
     * - Text:  10B, 50B, 100B, 500B, 1KB, 5KB
     * - Image: 10KB, 50KB, 100KB, 500KB
     *
     * @param repeats Number of messages to send per size category (default 10)
     * @param bidirectional If true, enables auto-reply on the receiving device so the session ratchets.
     */
    fun runBenchmark(
        peerId: String,
        repeats: Int = 10,
        bidirectional: Boolean = true,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) {
        if (runningJob?.isActive == true) {
            Log.w(TAG, "Stress test already running, ignoring")
            return
        }

        val textSizes = listOf(10, 50, 100, 500, 1024, 5120)
        val imageSizes = listOf(10_240, 51_200, 102_400, 512_000)
        val totalMessages = (textSizes.size + imageSizes.size) * repeats

        Log.i(TAG, "Starting BENCHMARK: ${textSizes.size} text sizes + ${imageSizes.size} image sizes, " +
            "$repeats repeats each = $totalMessages total messages to ${peerId.take(8)} (bidirectional=$bidirectional)")
        
        // Enable bidirectional mode if requested
        if (bidirectional) {
            wifiAwareManager.setBidirectionalStressTestMode(peerId)
        }

        PerformanceTracker.record(MetricEvent("stress_test", "benchmark_start", 0.0,
            metadata = mapOf(
                "peer_id" to peerId.take(8),
                "text_sizes" to textSizes.joinToString(","),
                "image_sizes" to imageSizes.joinToString(","),
                "repeats" to repeats.toString(),
                "total_messages" to totalMessages.toString(),
                "bidirectional" to bidirectional.toString()
            )))

        val startTime = System.nanoTime()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        runningJob = scope.launch {
            // ── Text messages of varying sizes ──────────────────────
            for (sizeBytes in textSizes) {
                if (!isActive) break
                val sizeLabel = if (sizeBytes >= 1024) "${sizeBytes / 1024}KB" else "${sizeBytes}B"
                Log.i(TAG, "BENCHMARK: Sending $repeats text messages of $sizeLabel")

                for (i in 1..repeats) {
                    if (!isActive) break
                    try {
                        val message = generateTextPayload(sizeBytes, i)
                        wifiAwareManager.sendMessageToPeer(message, peerId)
                        successCount.incrementAndGet()
                    } catch (e: Exception) {
                        failCount.incrementAndGet()
                        Log.w(TAG, "Benchmark text send failed ($sizeLabel #$i): ${e.message}")
                    }
                    // 500ms between sends to let each message complete the full pipeline
                    delay(500)
                }

                Log.i(TAG, "BENCHMARK: Completed $sizeLabel texts ($repeats sent)")
            }

            // ── Images of varying sizes ─────────────────────────────
            for (sizeBytes in imageSizes) {
                if (!isActive) break
                val sizeLabel = if (sizeBytes >= 1024) "${sizeBytes / 1024}KB" else "${sizeBytes}B"
                Log.i(TAG, "BENCHMARK: Sending $repeats images of $sizeLabel")

                val imageData = generateSyntheticImage(sizeBytes)

                for (i in 1..repeats) {
                    if (!isActive) break
                    try {
                        wifiAwareManager.sendPictureToPeer(
                            imageData,
                            "benchmark_${sizeLabel}_$i.jpg",
                            "image/jpeg",
                            peerId
                        )
                        successCount.incrementAndGet()
                    } catch (e: Exception) {
                        failCount.incrementAndGet()
                        Log.w(TAG, "Benchmark image send failed ($sizeLabel #$i): ${e.message}")
                    }
                    // 1s between image sends (larger payloads need more time)
                    delay(1000)
                }

                Log.i(TAG, "BENCHMARK: Completed $sizeLabel images ($repeats sent)")
            }

            // Wait for last async sends to complete
            delay(3000)

            val totalMs = (System.nanoTime() - startTime) / 1_000_000.0

            PerformanceTracker.record(MetricEvent("stress_test", "benchmark_end", totalMs,
                metadata = mapOf(
                    "peer_id" to peerId.take(8),
                    "total_messages" to totalMessages.toString(),
                    "success_count" to successCount.get().toString(),
                    "fail_count" to failCount.get().toString()
                )))

            PerformanceTracker.recordMemorySnapshot()
            
            // Disable bidirectional mode
            if (bidirectional) {
                wifiAwareManager.setBidirectionalStressTestMode(null)
            }
            
            Log.i(TAG, "BENCHMARK complete: ${successCount.get()}/$totalMessages in ${totalMs}ms " +
                "(failures: ${failCount.get()})")
        }
    }

    /** Cancel a running stress test. */
    fun cancel() {
        runningJob?.cancel()
        runningJob = null
        // Disable bidirectional mode when cancelling
        wifiAwareManager.setBidirectionalStressTestMode(null)
        Log.i(TAG, "Stress test cancelled")
    }

    /** Check if a stress test is currently running. */
    fun isRunning(): Boolean = runningJob?.isActive == true

    /**
     * Generate a text payload of approximately [sizeBytes] bytes.
     * Uses printable characters that look like realistic message content.
     */
    private fun generateTextPayload(sizeBytes: Int, index: Int): String {
        val prefix = "bench_${sizeBytes}B_#$index: "
        val remaining = sizeBytes - prefix.length
        if (remaining <= 0) return prefix.take(sizeBytes)
        // Use a mix of chars to prevent any compression artifacts
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,"
        val sb = StringBuilder(sizeBytes)
        sb.append(prefix)
        for (i in 0 until remaining) {
            sb.append(chars[(i * 31 + index * 7) % chars.length])
        }
        return sb.toString()
    }

    /**
     * Generate a synthetic byte array simulating image data.
     * Uses a repeating pattern so compression doesn't help.
     */
    private fun generateSyntheticImage(sizeBytes: Int): ByteArray {
        val data = ByteArray(sizeBytes)
        for (i in data.indices) {
            data[i] = ((i * 131 + 17) % 256).toByte()
        }
        return data
    }
}
