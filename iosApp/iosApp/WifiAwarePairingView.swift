import SwiftUI
import UIKit
#if canImport(DeviceDiscoveryUI)
import DeviceDiscoveryUI
#endif
#if canImport(WiFiAware)
import WiFiAware
#endif

/// Provides a UIViewController for Wi-Fi Aware device pairing using DeviceDiscoveryUI.
/// Called from Kotlin via ObjC interop.
@objc public class WifiAwarePairingController: NSObject {

    /// Creates a UIViewController that presents the system device pairing UI.
    /// Returns a placeholder on unsupported iOS versions.
    @objc public static func createPairingViewController() -> UIViewController {
        #if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
        if #available(iOS 26, *) {
            return makePairingHostingController()
        }
        #endif
        return makeUnsupportedController()
    }

    #if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
    @available(iOS 26, *)
    private static func makePairingHostingController() -> UIViewController {
        let hostingController = UIHostingController(rootView: NativePairingScreen(
            onPairingComplete: {
                // Will be replaced immediately to capture the hosting controller.
            },
            autoDismissOnPaired: false
        ))
        hostingController.rootView = NativePairingScreen(
            onPairingComplete: { [weak hostingController] in
                hostingController?.dismiss(animated: true)
            },
            autoDismissOnPaired: false
        )
        hostingController.modalPresentationStyle = .fullScreen
        return hostingController
    }
    #endif

    private static func makeUnsupportedController() -> UIViewController {
        let vc = UIViewController()
        vc.view.backgroundColor = .systemBackground
        let label = UILabel()
        label.text = "Wi-Fi Aware pairing requires iOS 26 or later"
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        vc.view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: vc.view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: vc.view.leadingAnchor, constant: 20),
            label.trailingAnchor.constraint(lessThanOrEqualTo: vc.view.trailingAnchor, constant: -20),
        ])
        return vc
    }
}

// MARK: - SwiftUI Pairing View

#if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
@available(iOS 26, *)
private struct WifiAwarePairingSwiftUIView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let service = WAPublishableService.trickService {
                    DevicePairingView(
                        .wifiAware(
                            .connecting(
                                to: service,
                                from: .selected([])
                            )
                        )
                    ) {
                        VStack(spacing: 16) {
                            Image(systemName: "wifi")
                                .font(.system(size: 60))
                                .foregroundColor(.accentColor)
                            Text("Pair with Nearby Device")
                                .font(.title2)
                                .fontWeight(.semibold)
                            Text("Tap to find and pair with a nearby Trick device using Wi-Fi Aware.")
                                .font(.body)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 32)
                        }
                        .padding()
                    } fallback: {
                        VStack(spacing: 16) {
                            Image(systemName: "wifi.exclamationmark")
                                .font(.system(size: 60))
                                .foregroundColor(.red)
                            Text("Wi-Fi Aware Unavailable")
                                .font(.title2)
                                .fontWeight(.semibold)
                            Text("This device does not support Wi-Fi Aware, or it is currently unavailable.")
                                .font(.body)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 32)
                        }
                        .padding()
                    }
                } else {
                    VStack(spacing: 16) {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.system(size: 60))
                            .foregroundColor(.red)
                        Text("Service Not Configured")
                            .font(.title2)
                            .fontWeight(.semibold)
                        Text("Wi-Fi Aware service '_trick-msg._tcp' is not configured. Check Info.plist.")
                            .font(.body)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                    .padding()
                }
            }
            .navigationTitle("Device Pairing")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}
#endif
