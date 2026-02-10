import SwiftUI
#if canImport(DeviceDiscoveryUI)
import DeviceDiscoveryUI
#endif
#if canImport(WiFiAware)
import WiFiAware
#endif
#if canImport(Network)
import Network
#endif

/// Native SwiftUI pairing screen shown before Compose loads.
///
/// Uses `DevicePairingView` in a pure SwiftUI `NavigationStack` so the Apple
/// system pairing UI works correctly (no UIHostingController layering).
/// Monitors `WAPairedDevice.allDevices(matching:)` to auto-detect pairing completion.
struct NativePairingScreen: View {
    let onPairingComplete: () -> Void
    let autoDismissOnPaired: Bool

    var body: some View {
        #if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
        if #available(iOS 26, *) {
            PairingContent(
                onPairingComplete: onPairingComplete,
                autoDismissOnPaired: autoDismissOnPaired
            )
        } else {
            unsupportedView
        }
        #else
        unsupportedView
        #endif
    }

    private var unsupportedView: some View {
        VStack(spacing: 16) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 60))
                .foregroundColor(.red)
            Text("Wi-Fi Aware Unavailable")
                .font(.title2)
                .fontWeight(.semibold)
            Text("Requires iOS 26+ and compatible hardware.")
                .font(.body)
                .foregroundColor(.secondary)
            Button("Continue Anyway") {
                onPairingComplete()
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 8)
        }
        .padding()
    }
}

#if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
@available(iOS 26, *)
private struct PairingContent: View {
    let onPairingComplete: () -> Void
    let autoDismissOnPaired: Bool
    @State private var monitorTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let pubService = WAPublishableService.trickService,
                   let subService = WASubscribableService.trickService {
                    ScrollView {
                        VStack(spacing: 24) {
                            // Header
                            VStack(spacing: 8) {
                                Image(systemName: "wifi")
                                    .font(.system(size: 50))
                                    .foregroundColor(.accentColor)
                                Text("Pair with Nearby Device")
                                    .font(.title2)
                                    .fontWeight(.semibold)
                                Text("Both devices must be on this screen. Tap a device below to pair.")
                                    .font(.body)
                                    .foregroundColor(.secondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 32)
                            }
                            .padding(.top, 16)

                            // DevicePicker - actively browses for nearby devices
                            DevicePicker(
                                .wifiAware(
                                    .connecting(to: .selected([]), from: subService)
                                )
                            ) { endpoint in
                                print("[NativePairingScreen] Device selected with endpoint: \(endpoint)")
                                // Pairing happens automatically, monitorPairedDevices will detect it
                            } label: {
                                VStack(spacing: 12) {
                                    Image(systemName: "magnifyingglass")
                                        .font(.system(size: 24))
                                    Text("Find Nearby Devices")
                                        .font(.headline)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(Color.accentColor)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                                .padding(.horizontal, 24)
                            } fallback: {
                                VStack(spacing: 8) {
                                    Image(systemName: "wifi.slash")
                                        .font(.system(size: 24))
                                        .foregroundColor(.secondary)
                                    Text("Device discovery unavailable")
                                        .font(.subheadline)
                                        .foregroundColor(.secondary)
                                }
                                .padding()
                            }

                            Divider()
                                .padding(.horizontal, 24)

                            // DevicePairingView - makes this device discoverable
                            DevicePairingView(
                                .wifiAware(
                                    .connecting(
                                        to: pubService,
                                        from: .selected([])
                                    )
                                )
                            ) {
                                VStack(spacing: 12) {
                                    Image(systemName: "antenna.radiowaves.left.and.right")
                                        .font(.system(size: 24))
                                        .foregroundColor(.accentColor)
                                    Text("Make This Device Discoverable")
                                        .font(.headline)
                                    Text("Tap to let other devices find you")
                                        .font(.subheadline)
                                        .foregroundColor(.secondary)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(Color(.systemGray6))
                                .cornerRadius(12)
                                .padding(.horizontal, 24)
                            } fallback: {
                                VStack(spacing: 8) {
                                    Image(systemName: "wifi.exclamationmark")
                                        .font(.system(size: 24))
                                        .foregroundColor(.secondary)
                                    Text("Advertising unavailable")
                                        .font(.subheadline)
                                        .foregroundColor(.secondary)
                                }
                                .padding()
                            }

                            Spacer(minLength: 24)
                        }
                    }
                } else {
                    VStack(spacing: 16) {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.system(size: 60))
                            .foregroundColor(.red)
                        Text("Service Not Configured")
                            .font(.title2)
                            .fontWeight(.semibold)
                        Text("Wi-Fi Aware service not found. Check Info.plist.")
                            .font(.body)
                            .foregroundColor(.secondary)
                    }
                    .padding()
                }
            }
            .navigationTitle("Device Pairing")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Skip") {
                        monitorTask?.cancel()
                        onPairingComplete()
                    }
                }
            }
            .task {
                guard autoDismissOnPaired else {
                    return
                }
                monitorTask = Task {
                    await monitorPairedDevices()
                }
                await monitorTask?.value
            }
        }
    }

    private func monitorPairedDevices() async {
        let filter = #Predicate<WAPairedDevice> { _ in true }
        do {
            for try await devices in WAPairedDevice.allDevices(matching: filter) {
                if !devices.isEmpty {
                    print("[NativePairingScreen] Paired device detected, transitioning to app")
                    await MainActor.run {
                        onPairingComplete()
                    }
                    return
                }
            }
        } catch is CancellationError {
            // Normal cancellation (user tapped Skip)
        } catch {
            print("[NativePairingScreen] Error monitoring paired devices: \(error)")
        }
    }
}
#endif
