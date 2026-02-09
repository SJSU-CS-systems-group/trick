import Foundation
import Network
import UIKit
import ComposeApp
import Security
#if canImport(WiFiAware)
import WiFiAware
#endif

// PSK configuration - must match Android's DeviceIdentity.getPskPassphrase()
private let PSK_PASSPHRASE = "KMPChatSecure2024"
private let PSK_IDENTITY = "trick-msg"

// MARK: - Role Negotiation (matching Android's DeviceIdentity)

/// Role for connection establishment - matches Android's Role enum
enum ConnectionRole {
    case server  // Higher hash - waits for handshake, then responds
    case client  // Lower hash - sends handshake first, then waits
    case none    // Error state
}

/// Deterministic role negotiation matching Android's DeviceIdentity.negotiateRole()
/// Both devices independently compute the same roles using hash comparison.
/// - Higher hash = SERVER (waits for handshake)
/// - Lower hash = CLIENT (initiates handshake)
func negotiateRole(localDeviceId: String, remoteDeviceId: String) -> ConnectionRole {
    let localHash = localDeviceId.hashValue
    let remoteHash = remoteDeviceId.hashValue
    
    if localHash > remoteHash {
        return .server
    } else if localHash < remoteHash {
        return .client
    } else {
        // Tie-breaker: lexicographic comparison (same as Android)
        if localDeviceId > remoteDeviceId {
            return .server
        } else {
            return .client
        }
    }
}

/// Swift bridge for Apple's Wi-Fi Aware framework (iOS 26+).
///
/// Conforms to `WifiAwareNativeBridge` (Kotlin-defined, exported as ObjC protocol).
/// Uses `NetworkListener` (publisher) and `NetworkBrowser` (subscriber) internally,
/// wrapping Swift structured-concurrency APIs and exposing them to Kotlin.
///
/// All data is opaque bytes — Signal encryption stays in Kotlin.
public class WifiAwareBridge: NSObject, WifiAwareNativeBridge {

    // Kotlin callback (WifiAwareNativeCallback protocol)
    public var nativeCallback: (any WifiAwareNativeCallback)?

    private var localDeviceId: String = ""
    private var desiredPeerId: String?
    private var isRunning = false

    // Connection tracking: peerId -> Task handling that connection
    private var connectionTasks: [String: Task<Void, Never>] = [:]
    // peerId -> active WiFi Aware connection (stored as Any for type erasure)
    private var connectionMap: [String: Any] = [:]

    private let connectionQueue = DispatchQueue(label: "net.discdd.trick.wifiaware.connections")

    private var heartbeatTimer: DispatchSourceTimer?

    // Async task handles for structured concurrency
    private var publisherTask: Task<Void, Never>?
    private var subscriberTask: Task<Void, Never>?
    
    // Active WiFi Aware connections for sending (stored by peerId)
    private var activeConnections: [String: Any] = [:]
    private let activeConnectionsLock = NSLock()
    
    // Pending handshakes to prevent duplicate attempts (matches Android's pendingHandshakes)
    private var pendingHandshakes: Set<String> = []
    private let pendingHandshakesLock = NSLock()

    // Retry limits to prevent infinite restart loops
    private var publisherRetries = 0
    private var subscriberRetries = 0
    private let maxRetries = 5

    private func log(_ message: String) {
        print("[WifiAwareBridge] \(message)")
    }

    // MARK: - TLS PSK Configuration
    
    /// Creates NWParameters configured with TLS 1.2 + PSK (Pre-Shared Key) authentication.
    /// This matches Android's WiFi Aware PSK configuration for iOS <-> iOS communication.
    private func createPSKParameters() -> NWParameters {
        let tlsOptions = NWProtocolTLS.Options()
        
        // Get the security protocol options
        let secOptions = tlsOptions.securityProtocolOptions
        
        // Force TLS 1.2 (iOS doesn't support TLS 1.3 + external PSK)
        sec_protocol_options_set_min_tls_protocol_version(secOptions, .TLSv12)
        sec_protocol_options_set_max_tls_protocol_version(secOptions, .TLSv12)
        
        // Add PSK authentication
        let pskData = Data(PSK_PASSPHRASE.utf8)
        let pskIdentityData = Data(PSK_IDENTITY.utf8)
        
        pskData.withUnsafeBytes { pskBytes in
            pskIdentityData.withUnsafeBytes { identityBytes in
                // Create dispatch_data_t for PSK
                let pskDispatchData = pskBytes.baseAddress.map {
                    DispatchData(bytes: UnsafeRawBufferPointer(start: $0, count: pskData.count))
                } ?? DispatchData.empty
                
                // Create dispatch_data_t for identity
                let identityDispatchData = identityBytes.baseAddress.map {
                    DispatchData(bytes: UnsafeRawBufferPointer(start: $0, count: pskIdentityData.count))
                } ?? DispatchData.empty
                
                sec_protocol_options_add_pre_shared_key(
                    secOptions,
                    pskDispatchData as __DispatchData,
                    identityDispatchData as __DispatchData
                )
            }
        }
        
        log("Created TLS 1.2 + PSK parameters")
        
        let tcpOptions = NWProtocolTCP.Options()
        return NWParameters(tls: tlsOptions, tcp: tcpOptions)
    }

    // MARK: - WifiAwareNativeBridge Protocol

    public func configure(localDeviceId: String) {
        self.localDeviceId = localDeviceId
    }

    public func startNativeDiscovery() {
        guard !isRunning else { return }
        isRunning = true
        publisherRetries = 0
        subscriberRetries = 0

        guard Self.isSupportedCheck() else {
            nativeCallback?.onNativeError(error: "Wi-Fi Aware is not supported on this device/OS version")
            isRunning = false
            return
        }

        #if canImport(WiFiAware)
        if #available(iOS 26, *) {
            startPublisher()
            startSubscriber()
            startHeartbeat()
            nativeCallback?.onNativeStatusUpdated(status: "Discovering")
        }
        #endif
    }

    public func stopNativeDiscovery() {
        guard isRunning else { return }
        isRunning = false

        log("Stopping discovery")

        heartbeatTimer?.cancel()
        heartbeatTimer = nil

        publisherTask?.cancel()
        publisherTask = nil

        subscriberTask?.cancel()
        subscriberTask = nil

        connectionQueue.sync {
            for (_, task) in connectionTasks {
                task.cancel()
            }
            connectionTasks.removeAll()
            connectionMap.removeAll()
        }
        
        activeConnectionsLock.lock()
        activeConnections.removeAll()
        activeConnectionsLock.unlock()

        nativeCallback?.onNativeStatusUpdated(status: "Stopped")
    }

    public func sendNativeData(data: Data, toPeerId: String) {
        #if canImport(WiFiAware)
        if #available(iOS 26, *) {
            activeConnectionsLock.lock()
            let conn = activeConnections[toPeerId] as? NetworkConnection<TCP>
            activeConnectionsLock.unlock()
            
            guard let conn = conn else {
                log("No active connection to peer \(toPeerId)")
                nativeCallback?.onNativeError(error: "No connection to peer \(toPeerId)")
                return
            }
            
            Task {
                do {
                    try await self.sendFramedWA(data: data, on: conn)
                    self.log("Sent \(data.count) bytes to \(toPeerId)")
                } catch {
                    self.log("Send error to \(toPeerId): \(error)")
                    self.handleDisconnect(peerId: toPeerId)
                }
            }
        } else {
            nativeCallback?.onNativeError(error: "WiFi Aware requires iOS 26+")
        }
        #else
        nativeCallback?.onNativeError(error: "WiFi Aware not available")
        #endif
    }

    public func sendNativeDataToAll(data: Data) {
        #if canImport(WiFiAware)
        if #available(iOS 26, *) {
            activeConnectionsLock.lock()
            let peers: [(String, NetworkConnection<TCP>)] = activeConnections.compactMap { (key, value) in
                guard let conn = value as? NetworkConnection<TCP> else { return nil }
                return (key, conn)
            }
            activeConnectionsLock.unlock()
            
            Task {
                for (peerId, conn) in peers {
                    do {
                        try await self.sendFramedWA(data: data, on: conn)
                    } catch {
                        self.log("Send error to \(peerId): \(error)")
                        self.handleDisconnect(peerId: peerId)
                    }
                }
            }
        }
        #endif
    }

    public func setNativeDesiredPeerId(peerId: String?) {
        self.desiredPeerId = peerId
    }

    public func isNativePeerConnected(peerId: String) -> Bool {
        activeConnectionsLock.lock()
        let connected = activeConnections[peerId] != nil
        activeConnectionsLock.unlock()
        return connected
    }

    public func getNativeConnectedPeerIds() -> [String] {
        activeConnectionsLock.lock()
        let peers = Array(activeConnections.keys)
        activeConnectionsLock.unlock()
        return peers
    }

    public func getNativeConnectionStatus() -> String {
        activeConnectionsLock.lock()
        let count = activeConnections.count
        activeConnectionsLock.unlock()
        if count == 0 { return "No connections" }
        return "Connections: \(count)"
    }

    public func isNativeSupported() -> Bool {
        return Self.isSupportedCheck()
    }

    private static func isSupportedCheck() -> Bool {
        #if canImport(WiFiAware)
        if #available(iOS 26, *) {
            guard WACapabilities.supportedFeatures.contains(.wifiAware) else {
                return false
            }
            guard WAPublishableService.allServices["_trick-msg._tcp"] != nil else {
                print("[WifiAwareBridge] Service '_trick-msg._tcp' not found in Info.plist")
                return false
            }
            return true
        }
        #endif
        return false
    }

    // MARK: - Publisher (NetworkListener)

    #if canImport(WiFiAware)
    @available(iOS 26, *)
    private func startPublisher() {
        guard let service = WAPublishableService.trickService else {
            DispatchQueue.main.async { [weak self] in
                self?.nativeCallback?.onNativeError(
                    error: "Publisher failed: Service '_trick-msg._tcp' not found. Check Info.plist."
                )
            }
            return
        }

        log("Starting publisher (attempt \(publisherRetries + 1)/\(maxRetries + 1))")

        let deviceFilter = #Predicate<WAPairedDevice> { _ in true }

        publisherTask = Task { [weak self] in
            guard let self = self else { return }
            do {
                // Use TCP without TLS - Signal protocol provides E2E encryption
                // TLS certificate validation fails in peer-to-peer WiFi Aware
                let listener = try NetworkListener(
                    for: .wifiAware(
                        .connecting(to: service, from: .matching(deviceFilter))
                    ),
                    using: .parameters {
                        TCP()
                    }
                )
                .onStateUpdate { [weak self] _, state in
                    switch state {
                    case .ready:
                        DispatchQueue.main.async {
                            self?.nativeCallback?.onNativeStatusUpdated(status: "Publishing")
                        }
                    case .failed(let error):
                        let errorDescription = "Publisher failed: \(error)"
                        self?.log(errorDescription)
                        DispatchQueue.main.async {
                            self?.nativeCallback?.onNativeError(error: errorDescription)
                        }
                    default:
                        break
                    }
                }

                try await listener.run { [weak self] connection in
                    guard let self = self else { return }
                    self.publisherRetries = 0 // Reset on successful connection
                    self.log("Publisher received incoming connection")
                    await self.handleConnection(connection, isInitiator: false)
                }
            } catch is CancellationError {
                // Normal cancellation
            } catch {
                self.log("Publisher error: \(error)")
                DispatchQueue.main.async {
                    self.nativeCallback?.onNativeError(error: "Publisher error: \(error)")
                }
                if self.isRunning && self.publisherRetries < self.maxRetries {
                    self.publisherRetries += 1
                    try? await Task.sleep(for: .seconds(2))
                    if self.isRunning && !Task.isCancelled {
                        self.startPublisher()
                    }
                } else if self.publisherRetries >= self.maxRetries {
                    self.log("Publisher reached max retries (\(self.maxRetries))")
                }
            }
        }
    }

    // MARK: - Subscriber (NetworkBrowser)

    @available(iOS 26, *)
    private func startSubscriber() {
        guard let service = WASubscribableService.trickService else {
            DispatchQueue.main.async { [weak self] in
                self?.nativeCallback?.onNativeError(
                    error: "Browser failed: Service '_trick-msg._tcp' not found. Check Info.plist."
                )
            }
            return
        }

        log("Starting subscriber (attempt \(subscriberRetries + 1)/\(maxRetries + 1))")

        subscriberTask = Task { [weak self] in
            guard let self = self else { return }
            do {
                // First, check if we have any paired devices
                let deviceFilter = #Predicate<WAPairedDevice> { _ in true }
                var hasPairedDevices = false
                
                for try await devices in WAPairedDevice.allDevices(matching: deviceFilter) {
                    hasPairedDevices = !devices.isEmpty
                    self.log("Paired devices check: found \(devices.count) device(s)")
                    break // Just check once
                }
                
                guard hasPairedDevices else {
                    self.log("No paired devices found, will retry in 5 seconds")
                    if self.isRunning {
                        try? await Task.sleep(for: .seconds(5))
                        if self.isRunning && !Task.isCancelled {
                            self.startSubscriber()
                        }
                    }
                    return
                }
                
                self.log("Creating browser for paired devices")
                
                let browser = NetworkBrowser(
                    for: .wifiAware(
                        .connecting(to: .matching(deviceFilter), from: service)
                    )
                )
                .onStateUpdate { [weak self] _, state in
                    switch state {
                    case .ready:
                        DispatchQueue.main.async {
                            self?.nativeCallback?.onNativeStatusUpdated(status: "Browsing")
                        }
                    case .failed(let error):
                        let errorDescription = "Browser failed: \(error)"
                        self?.log(errorDescription)
                        DispatchQueue.main.async {
                            self?.nativeCallback?.onNativeError(error: errorDescription)
                        }
                    default:
                        break
                    }
                }

                let endpoint = try await browser.run { waEndpoints in
                    if let ep = waEndpoints.first {
                        return .finish(ep)
                    }
                    return .continue
                }

                self.subscriberRetries = 0
                self.log("Found endpoint, creating connection with TCP")
                
                // Check if we already have a connection (avoid duplicates like Android)
                // Note: We don't know the remote peer ID yet, but we can check pending handshakes
                // The actual duplicate check happens in handleConnection after handshake

                let connection = NetworkConnection(to: endpoint, using: .parameters {
                    TCP()
                })

                let task = Task { [weak self] in
                    guard let self = self else { return }
                    await self.handleConnection(connection, isInitiator: true)
                }
                _ = task

                try? await Task.sleep(for: .seconds(5))
                
                if self.isRunning && !Task.isCancelled {
                    self.startSubscriber()
                }
            } catch is CancellationError {
                // Normal cancellation
            } catch {
                self.log("Browser error: \(error)")
                DispatchQueue.main.async {
                    self.nativeCallback?.onNativeError(error: "Browser error: \(error)")
                }
                if self.isRunning && self.subscriberRetries < self.maxRetries {
                    self.subscriberRetries += 1
                    self.log("Subscriber retry \(self.subscriberRetries)/\(self.maxRetries) after error")
                    try? await Task.sleep(for: .seconds(3))
                    if self.isRunning && !Task.isCancelled {
                        self.startSubscriber()
                    }
                } else if self.subscriberRetries >= self.maxRetries {
                    self.log("Subscriber reached max retries (\(self.maxRetries))")
                }
            }
        }
    }
    #endif

    // MARK: - Connection Handling (async/await with NetworkConnection<TCP>)
    
    /// Role-based handshake protocol matching Android's implementation:
    /// - SERVER (higher hash): Waits for handshake first, then sends response
    /// - CLIENT (lower hash): Sends handshake first, then waits for response
    /// This prevents deadlock where both sides send-then-wait simultaneously.

    #if canImport(WiFiAware)
    @available(iOS 26, *)
    private func handleConnection(_ connection: NetworkConnection<TCP>, isInitiator: Bool) async {
        var registeredPeerId: String?
        
        log("Handling connection (isInitiator: \(isInitiator))")

        do {
            let remotePeerId: String
            
            // Phase 1: Exchange device IDs to determine roles
            // Both sides need to know each other's ID to compute roles deterministically
            if isInitiator {
                // Initiator (subscriber/browser) sends ID first
                let handshakeStr = "HANDSHAKE:\(self.localDeviceId)"
                let handshakeData = Data(handshakeStr.utf8)
                log("Initiator: Sending handshake...")
                try await sendFramedWA(data: handshakeData, on: connection)
                
                log("Initiator: Waiting for handshake response...")
                let firstMessage = try await receiveFramedWA(on: connection)
                
                guard let str = String(data: firstMessage, encoding: .utf8),
                      str.hasPrefix("HANDSHAKE:") else {
                    log("Initiator: Invalid handshake response")
                    return
                }
                remotePeerId = String(str.dropFirst("HANDSHAKE:".count))
                log("Initiator: Received peer ID: \(remotePeerId)")
            } else {
                // Non-initiator (publisher/listener) receives first, then sends
                log("Non-initiator: Waiting for handshake...")
                let firstMessage = try await receiveFramedWA(on: connection)
                
                guard let str = String(data: firstMessage, encoding: .utf8),
                      str.hasPrefix("HANDSHAKE:") else {
                    log("Non-initiator: Invalid handshake")
                    return
                }
                remotePeerId = String(str.dropFirst("HANDSHAKE:".count))
                log("Non-initiator: Received peer ID: \(remotePeerId)")
                
                let handshakeStr = "HANDSHAKE:\(self.localDeviceId)"
                let handshakeData = Data(handshakeStr.utf8)
                log("Non-initiator: Sending handshake response...")
                try await sendFramedWA(data: handshakeData, on: connection)
            }
            
            // Compute role deterministically (both sides compute same result)
            let role = negotiateRole(localDeviceId: self.localDeviceId, remoteDeviceId: remotePeerId)
            log("Handshake complete with peer: \(remotePeerId), role: \(role), isInitiator: \(isInitiator)")

            if let desired = desiredPeerId, desired != remotePeerId {
                log("Peer doesn't match desired peer")
                return
            }
            
            // Check for duplicate connections BEFORE storing
            activeConnectionsLock.lock()
            let existingConnection = activeConnections[remotePeerId] != nil
            activeConnectionsLock.unlock()
            
            if existingConnection {
                log("Already have connection to \(remotePeerId), skipping duplicate")
                return
            }
            
            // Remove from pending handshakes
            pendingHandshakesLock.lock()
            pendingHandshakes.remove(remotePeerId)
            pendingHandshakesLock.unlock()

            // Store connection for sending
            activeConnectionsLock.lock()
            activeConnections[remotePeerId] = connection
            activeConnectionsLock.unlock()
            
            registeredPeerId = remotePeerId
            connectionQueue.sync {
                connectionMap[remotePeerId] = true // Mark as connected
            }

            DispatchQueue.main.async { [weak self] in
                self?.nativeCallback?.onNativePeerConnected(peerId: remotePeerId)
                self?.nativeCallback?.onNativeStatusUpdated(
                    status: "Connected to \(String(remotePeerId.prefix(8)))"
                )
            }
            
            log("Starting receive loop for peer: \(remotePeerId)")

            // Receive loop - both sides can send/receive on this connection
            while !Task.isCancelled {
                let payload = try await receiveFramedWA(on: connection)
                log("Received \(payload.count) bytes from \(remotePeerId)")
                DispatchQueue.main.async { [weak self] in
                    self?.nativeCallback?.onNativeDataReceived(data: payload, fromPeerId: remotePeerId)
                }
            }
        } catch is CancellationError {
            log("Connection cancelled")
        } catch {
            log("Connection failed: \(error)")
        }

        if let peerId = registeredPeerId {
            handleDisconnect(peerId: peerId)
        }
    }

    // MARK: - Message Framing: [4-byte big-endian length][payload]

    @available(iOS 26, *)
    private func sendFramedWA(data: Data, on connection: NetworkConnection<TCP>) async throws {
        var length = UInt32(data.count).bigEndian
        var frame = Data(bytes: &length, count: 4)
        frame.append(data)
        try await connection.send(frame)
    }

    @available(iOS 26, *)
    private func receiveFramedWA(on connection: NetworkConnection<TCP>) async throws -> Data {
        let headerData = try await connection.receive(exactly: 4).content

        // Guard against incomplete reads (e.g., connection closed mid-frame)
        guard headerData.count >= 4 else {
            throw NSError(
                domain: "WifiAwareBridge",
                code: -2,
                userInfo: [NSLocalizedDescriptionKey: "Incomplete header: expected 4 bytes, got \(headerData.count)"]
            )
        }

        let length = headerData.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }

        // Heartbeat (zero-length frame) — skip
        if length == 0 {
            return try await receiveFramedWA(on: connection)
        }

        guard length <= 10_000_000 else {
            throw NSError(domain: "WifiAwareBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: "Frame too large"])
        }

        let payload = try await connection.receive(exactly: Int(length)).content
        
        // Guard against incomplete payload reads
        guard payload.count == Int(length) else {
            throw NSError(
                domain: "WifiAwareBridge",
                code: -3,
                userInfo: [NSLocalizedDescriptionKey: "Incomplete payload: expected \(length) bytes, got \(payload.count)"]
            )
        }
        
        return payload
    }
    #endif

    // MARK: - Heartbeat

    private func startHeartbeat() {
        #if canImport(WiFiAware)
        guard #available(iOS 26, *) else { return }
        
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global())
        timer.schedule(deadline: .now() + 30, repeating: 30)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            
            self.activeConnectionsLock.lock()
            let peers: [(String, NetworkConnection<TCP>)] = self.activeConnections.compactMap { (key, value) in
                guard let conn = value as? NetworkConnection<TCP> else { return nil }
                return (key, conn)
            }
            self.activeConnectionsLock.unlock()
            
            Task {
                let heartbeat = Data(count: 0)
                for (_, conn) in peers {
                    do {
                        try await self.sendFramedWA(data: heartbeat, on: conn)
                    } catch {
                        // Connection will be cleaned up by its receive loop
                    }
                }
            }
        }
        timer.resume()
        self.heartbeatTimer = timer
        #endif
    }

    // MARK: - Connection Cleanup

    private func handleDisconnect(peerId: String) {
        activeConnectionsLock.lock()
        activeConnections.removeValue(forKey: peerId)
        activeConnectionsLock.unlock()
        
        // Also clean up pending handshakes
        pendingHandshakesLock.lock()
        pendingHandshakes.remove(peerId)
        pendingHandshakesLock.unlock()
        
        connectionQueue.async { [weak self] in
            guard let self = self else { return }
            self.connectionMap.removeValue(forKey: peerId)
            self.connectionTasks[peerId]?.cancel()
            self.connectionTasks.removeValue(forKey: peerId)

            DispatchQueue.main.async {
                self.nativeCallback?.onNativePeerDisconnected(peerId: peerId)
                self.nativeCallback?.onNativeStatusUpdated(
                    status: "Disconnected from \(String(peerId.prefix(8)))"
                )
            }

            if self.isRunning {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    self.nativeCallback?.onNativeStatusUpdated(status: "Reconnecting...")
                }
            }
        }
    }
}
