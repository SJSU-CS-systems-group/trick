package net.discdd.trick.metrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.discdd.trick.screens.messaging.AndroidWifiAwareManager
import java.util.concurrent.atomic.AtomicReference

/**
 * BroadcastReceiver to trigger stress tests and benchmarks via ADB.
 *
 * IMPORTANT: peer_id can be a short ID (first 8 chars) — it will be resolved to
 * the full device ID from the connection pool. If omitted, uses the first connected peer.
 *
 * Usage — burst test:
 *   adb shell am broadcast -a net.discdd.trick.STRESS_TEST \
 *       --es peer_id "<short_or_full_peer_id>" \
 *       --ei text_count 100 \
 *       --ei image_count 10 \
 *       --ei image_size_kb 500
 *
 * Usage — ramp test:
 *   adb shell am broadcast -a net.discdd.trick.STRESS_TEST \
 *       --es peer_id "<short_or_full_peer_id>" \
 *       --es mode "ramp" \
 *       --ei ramp_max_rate 50 \
 *       --ei ramp_step_sec 5
 *
 * Usage — benchmark (varying message sizes through full E2E encrypted pipeline):
 *   adb shell am broadcast -a net.discdd.trick.STRESS_TEST \
 *       --es mode "benchmark" \
 *       --ei repeats 10
 *
 * Cancel a running test:
 *   adb shell am broadcast -a net.discdd.trick.STRESS_TEST_CANCEL
 */
class StressTestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PerfStressTest"
        const val ACTION_STRESS_TEST = "net.discdd.trick.STRESS_TEST"
        const val ACTION_CANCEL = "net.discdd.trick.STRESS_TEST_CANCEL"

        /**
         * Register the [AndroidWifiAwareManager] so the receiver can create a [StressTestRunner].
         * Called from [WifiAwareServiceImpl] on construction.
         */
        private val managerRef = AtomicReference<AndroidWifiAwareManager?>(null)
        private var runner: StressTestRunner? = null

        fun registerManager(manager: AndroidWifiAwareManager) {
            managerRef.set(manager)
        }
    }

    /**
     * Resolve a peer_id argument to a full device ID.
     * Accepts: full ID, short ID (8 chars), or null (auto-select first connected peer).
     */
    private fun resolveFullPeerId(manager: AndroidWifiAwareManager, rawPeerId: String?): String? {
        val connectedPeers = manager.getConnectedPeers()
        Log.i(TAG, "  Connected peers (${connectedPeers.size}): ${connectedPeers.map { it.take(8) }}")

        if (connectedPeers.isEmpty()) {
            Log.e(TAG, "No connected peers — make sure devices are connected on the messaging screen")
            return null
        }

        if (rawPeerId.isNullOrBlank()) {
            // Auto-select the first connected peer
            val fullId = connectedPeers.first()
            Log.i(TAG, "  No peer_id specified, auto-selected: ${fullId.take(8)}")
            return fullId
        }

        // Try exact match first (full ID)
        if (connectedPeers.contains(rawPeerId)) {
            return rawPeerId
        }

        // Try short ID resolution
        val resolved = manager.resolveShortPeerId(rawPeerId)
        if (resolved != null) {
            Log.i(TAG, "  Resolved short ID '$rawPeerId' → '${resolved.take(8)}...'")
            return resolved
        }

        Log.e(TAG, "Cannot resolve peer_id '$rawPeerId' — no matching connected peer")
        Log.e(TAG, "  Available peers: ${connectedPeers.map { it.take(8) }}")
        return null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, ">>> StressTestReceiver.onReceive CALLED <<<")
        Log.i(TAG, "  action=${intent?.action}")
        Log.i(TAG, "  managerRef=${if (managerRef.get() != null) "registered" else "NULL"}")

        if (context == null || intent == null) {
            Log.e(TAG, "Null context or intent — aborting")
            return
        }

        try {
            when (intent.action) {
                ACTION_STRESS_TEST -> {
                    val manager = managerRef.get()
                    if (manager == null) {
                        Log.e(TAG, "AndroidWifiAwareManager not registered — is the app running and on the messaging screen?")
                        return
                    }

                    val rawPeerId = intent.getStringExtra("peer_id")
                    val peerId = resolveFullPeerId(manager, rawPeerId)
                    if (peerId == null) {
                        return  // Error already logged
                    }

                    if (runner == null) {
                        Log.i(TAG, "  Creating new StressTestRunner")
                        runner = StressTestRunner(manager)
                    }

                    val mode = intent.getStringExtra("mode") ?: "burst"
                    Log.i(TAG, "  mode=$mode, resolved peer_id=${peerId.take(8)}")

                    when (mode) {
                        "burst" -> {
                            val textCount = intent.getIntExtra("text_count", 100)
                            val imageCount = intent.getIntExtra("image_count", 0)
                            val imageSizeKb = intent.getIntExtra("image_size_kb", 100)
                            Log.i(TAG, "  burst: textCount=$textCount, imageCount=$imageCount, imageSizeKb=$imageSizeKb")
                            runner?.runBurst(peerId, textCount, imageCount, imageSizeKb)
                        }
                        "ramp" -> {
                            val maxRate = intent.getIntExtra("ramp_max_rate", 50)
                            val stepSec = intent.getIntExtra("ramp_step_sec", 5)
                            Log.i(TAG, "  ramp: maxRate=$maxRate, stepSec=$stepSec")
                            runner?.runRamp(peerId, maxRate, stepSec)
                        }
                        "benchmark" -> {
                            val repeats = intent.getIntExtra("repeats", 10)
                            Log.i(TAG, "  benchmark: repeats=$repeats per size category")
                            runner?.runBenchmark(peerId, repeats)
                        }
                        else -> {
                            Log.e(TAG, "Unknown stress test mode: $mode (use 'burst', 'ramp', or 'benchmark')")
                        }
                    }
                }

                ACTION_CANCEL -> {
                    Log.i(TAG, "Cancelling stress test")
                    runner?.cancel()
                }

                else -> {
                    Log.w(TAG, "Unknown action: ${intent.action}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in StressTestReceiver: ${e.message}", e)
        }
    }
}
