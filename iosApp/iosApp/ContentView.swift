import UIKit
import SwiftUI
import ComposeApp
#if canImport(WiFiAware)
import WiFiAware
#endif

struct ComposeView: UIViewControllerRepresentable {
    let bridge: WifiAwareBridge

    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController(bridge: bridge)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @State private var isPaired = false
    @State private var isCheckingPairing = true

    private let bridge = WifiAwareBridge()

    var body: some View {
        Group {
            if isCheckingPairing {
                ProgressView("Checking pairing status...")
            } else if isPaired {
                ComposeView(bridge: bridge)
                    .ignoresSafeArea()
            } else {
                NativePairingScreen {
                    isPaired = true
                }
            }
        }
        .task {
            await checkPairingStatus()
        }
    }

    private func checkPairingStatus() async {
        #if canImport(WiFiAware)
        if #available(iOS 26, *) {
            guard WACapabilities.supportedFeatures.contains(.wifiAware) else {
                // Device doesn't support WiFi Aware — skip pairing
                isPaired = true
                isCheckingPairing = false
                return
            }

            let filter = #Predicate<WAPairedDevice> { _ in true }
            do {
                for try await devices in WAPairedDevice.allDevices(matching: filter) {
                    isPaired = !devices.isEmpty
                    isCheckingPairing = false
                    return
                }
            } catch {
                print("[ContentView] Error checking paired devices: \(error)")
            }
        }
        #endif

        // Fallback: no WiFi Aware or pre-iOS 26 — skip pairing
        isPaired = true
        isCheckingPairing = false
    }
}
