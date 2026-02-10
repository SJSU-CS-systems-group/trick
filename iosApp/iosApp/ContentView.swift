import UIKit
import SwiftUI
import ComposeApp
#if canImport(WiFiAware)
import WiFiAware
#endif

struct ComposeView: UIViewControllerRepresentable {
    let bridge: WifiAwareBridge
    let imagePicker: ImagePickerCoordinator

    func makeUIViewController(context: Context) -> UIViewController {
        let viewController = MainViewControllerKt.MainViewController(bridge: bridge, imagePicker: imagePicker)
        // Update the image picker's presenting view controller reference
        return viewController
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @State private var isPaired = false
    @State private var isCheckingPairing = true

    private let bridge = WifiAwareBridge()
    private let imagePicker = ImagePickerCoordinator(presentingViewController: nil)

    var body: some View {
        Group {
            if isCheckingPairing {
                ProgressView("Checking pairing status...")
            } else if isPaired {
                ComposeView(bridge: bridge, imagePicker: imagePicker)
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

    @MainActor
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
