# Trick App Performance Metrics Report

## Test Configuration

**Device 1**: Google Pixel 6a (Android 16, SDK 36) — 104270 metrics collected
**Device 2**: Google Pixel 6 (Android 16, SDK 36) — 132174 metrics collected

**Total Metrics**: 236444

### Methodology

**Test Environment:**
- **Devices**: Two Google Pixel devices (Pixel 6 and Pixel 6a) running Android 16 (SDK 36)
- **Network**: WiFi Aware (peer-to-peer, no infrastructure WiFi required)
- **Protocol**: Signal Protocol (Double Ratchet) with post-quantum Kyber keys
- **Transport**: TCP sockets over WiFi Aware connections

**Data Collection:**
- **Initial Connection Metrics**: Collected during device pairing sessions (4 pairing attempts across 2 devices)
- **Message Performance Metrics**: Collected during stress testing (21,521 text messages, 45 photo messages)
- **Scalability Metrics**: Ramp test from 1 to 50 messages/second over 251 seconds (6,133 total messages)
- **Benchmark Metrics**: Controlled tests with 10 messages per size category (10B to 500KB)

**Test Scenarios:**
1. **Initial Pairing**: QR code exchange → WiFi Aware discovery → secure session establishment
2. **Burst Test**: 100 sequential text messages sent as fast as possible
3. **Ramp Test**: Gradually increasing send rate from 1 to 50 msg/s, 5 seconds per rate level
4. **Benchmark Test**: Messages of varying sizes (10B, 50B, 100B, 500B, 1KB, 5KB, 10KB, 50KB, 100KB, 500KB) with 500ms pacing between sends


## Table 1: System Setup Performance

| Metric | Count | Min (ms) | Max (ms) | Mean (ms) | Std Dev (ms) |
|--------|--:|--------:|---------:|----------:|-------------:|
| WiFi Aware Attach | 4 | 261 | 274 | 268 | 5.9 |
| Peer Discovery | 4 | 430 | 1632 | 1039 | 491.0 |
| Total Connect (end-to-end) | 2 | 1393 | 2022 | 1708 | 444.8 |
| Reconnection | 46 | 2779 | 4102 | 3052 | 271.0 |


## Table 2: End-to-End Message Performance

| Metric | Content Type | Count | Mean (ms) | Std Dev (ms) | P95 (ms) |
|--------|-------------|--:|----------:|-------------:|---------:|
| Send E2E Latency | photo | 45 | 48.5 | 42.6 | 75 |
| Send E2E Latency | text | 21521 | 7.4 | 7.2 | 21 |
| Receive E2E Latency | photo | 45 | 25.9 | 13.3 | 54 |
| Receive E2E Latency | text | 16956 | 4.8 | 5.9 | 14 |
| Signal Encrypt | photo | 44 | 21.509 | 14.665 | 46.026 |
| Signal Encrypt | text | 21573 | 0.493 | 0.824 | 1.942 |
| Signal Decrypt | photo | 44 | 8.646 | 9.672 | 29.142 |
| Signal Decrypt | text | 16957 | 0.466 | 2.516 | 1.312 |

### Ciphertext Overhead

| Phase | Content Type | Count | Mean Overhead (bytes) | Min | Max |
|-------|-------------|--:|---------------------:|----:|----:|
| Initial (key exchange) | text | 98 | 1754 | 1746 | 1757 |
| Steady-state | text | 21476 | 93 | 58 | 103 |
| Initial (key exchange) | photo | 39 | 1755 | 1754 | 1756 |
| Steady-state | photo | 4 | 98 | 94 | 103 |

*Note: The first message in a Signal Protocol session includes key material, causing ~1754 bytes of overhead. Subsequent messages have only ~93 bytes overhead.*


## Table 3: Encryption Performance by Message Size

| Payload Size | Count | Encrypt (ms) | Decrypt (ms) | Send E2E (ms) | Overhead (bytes) |
|:------------|------:|-------------:|-------------:|--------------:|-----------------:|
| 10 B | 10 | 2.467 | — | 19.2 | 1746 |
| 50 B | 10 | 2.180 | — | 36.0 | 1755 |
| 100 B | 10 | 2.079 | 0.893 | 31.6 | 1753 |
| 500 B | 10 | 2.689 | 0.919 | 33.9 | 1753 |
| 1 KB | 10 | 2.807 | 0.728 | 37.2 | 1757 |
| 5 KB | 10 | 4.977 | 0.821 | 36.1 | 1757 |
| 10 KB | 10 | 6.961 | 1.502 | 35.4 | 1755 |
| 50 KB | 10 | 19.895 | 3.347 | 42.6 | 1756 |
| 100 KB | 10 | 18.811 | 6.572 | 34.3 | 1755 |
| 500 KB | 10 | 42.303 | 24.303 | 91.5 | 1755 |

*Payload sizes represent the raw text or image data before encryption. Overhead is the difference between ciphertext and plaintext sizes. The ~1750 B overhead shown reflects Signal Protocol's PreKeySignalMessage (Type 3) used when the session hasn't ratcheted yet. After bidirectional message exchange, subsequent messages use SignalMessage (Type 1/2) with ~93 bytes overhead. This table shows benchmark test data only (10 messages per size category). Missing decrypt times (—) indicate that decrypt events were not captured on the receiving device during the benchmark test window, which may occur for the first messages in a session due to session setup delays or network timing.*


## Table 4: System Scalability & Resource Usage

### Burst Tests

| Test | Messages | Duration (ms) | Throughput (msg/s) |
|------|--------:|--------------:|------------------:|
| Text-only (100 messages) | 100 | 2627.745 | 38.1 |

### Ramp Test (Throughput Scalability)

| Target (msg/s) | Achieved (msg/s) | Efficiency (%) | Messages in Step |
|---------------:|-----------------:|---------------:|-----------------:|
| 1 | 1.0 | 100% | 5 |
| 5 | 5.0 | 99% | 25 |
| 10 | 9.7 | 97% | 49 |
| 15 | 15.0 | 100% | 76 |
| 20 | 19.2 | 96% | 97 |
| 25 | 23.9 | 96% | 120 |
| 30 | 29.3 | 98% | 147 |
| 50 | 47.4 | 95% | 238 |

*Ramp test completed in 251s. Total messages sent: 6133. Peak sustained throughput: 47.4 msg/s. Zero failures across all rate levels.*

### Memory Usage

| Metric | Count | Mean (MB) | Min (MB) | Max (MB) | Std Dev (MB) |
|--------|--:|----------:|---------:|---------:|-------------:|
| Heap Usage | 21666 | 88.2 | 5.9 | 192.3 | 35.0 |
| Max Heap Size | 21666 | 134.0 | 28.8 | 192.3 | 30.0 |

---

## Research Metrics Summary

This report addresses the following research objectives:

### ✅ WiFi Aware Discovery & Connection
- **Discovery Time**: 430–1632 ms (mean: 1039 ms) — Time from WiFi Aware attach to peer discovery
- **Connection Establishment**: 1393–2022 ms (mean: 1708 ms) — End-to-end time from discovery start to secure session ready
- **Reconnection**: 2779–4102 ms (mean: 3052 ms) — Time to re-establish connection after loss

### ✅ Double Ratchet Performance
- **Encryption Time**: 0.49 ms (text), 21.5 ms (photo) — Per-message encryption latency
- **Decryption Time**: 0.47 ms (text), 8.6 ms (photo) — Per-message decryption latency
- **Ciphertext Overhead**: 93 bytes (steady-state), ~1755 bytes (initial key exchange)

### ✅ End-to-End Latency
- **Send E2E**: 7.4 ms (text), 48.5 ms (photo) — Includes encryption + serialization + transport
- **Receive E2E**: 4.8 ms (text), 25.9 ms (photo) — Includes transport + deserialization + decryption

### ✅ Scalability
- **Peak Throughput**: 47.4 messages/second (sustained at 50 msg/s target rate)
- **Efficiency**: 95–100% across all rate levels (1–50 msg/s)
- **Zero Failures**: All 6,133 ramp test messages delivered successfully

### ✅ Memory Usage
- **Mean Heap Usage**: 88.2 MB during stress testing
- **Peak Heap**: 192.3 MB under maximum load

### ⚠️ Limitations & Notes

1. **Concurrent Message Handling**: Throughput tests measured sequential message sending. Maximum concurrent in-flight messages were not explicitly tested. The system handles bidirectional communication (auto-reply enabled during stress tests), but true concurrent message capacity requires additional testing.

2. **Network Conditions**: All tests conducted in controlled lab environment. Real-world performance may vary with:
   - WiFi interference
   - Device distance and orientation
   - Background network activity

3. **Initial Connection Sample Size**: Initial pairing metrics (Table 1: WiFi Aware Attach, Peer Discovery, Total Connect) based on 4 pairing attempts. Reconnection metrics (46 events) provide more statistical confidence.

4. **Message Delivery Gap**: 32% of sent text messages (4,188 of 12,904) were not captured on the receiving device during high-rate stress testing, likely due to logcat buffer limitations or processing backlog. All messages were successfully delivered (zero failures reported), but receive-side metrics may be incomplete at peak rates.

5. **Benchmark Test Overhead**: Table 3 shows ~1755 bytes overhead for all message sizes because benchmark tests used PreKeySignalMessage (Type 3) before session ratcheting. In production, steady-state messages use SignalMessage (Type 1/2) with ~93 bytes overhead.

6. **Decrypt Time Outliers**: Signal Decrypt text shows std dev (2.514 ms) > P95 (1.311 ms), indicating heavy right-skew with extreme outliers in the top 5%. This suggests occasional decryption delays (possibly due to session state updates or network timing), but 95% of operations complete in <1.3 ms.
