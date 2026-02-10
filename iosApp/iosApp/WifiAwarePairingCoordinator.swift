import UIKit
import ComposeApp

/// Coordinator that presents the native WiFi Aware pairing UI.
@objc public class WifiAwarePairingCoordinator: NSObject, WifiAwarePairingPresenter {
    private weak var currentPairingController: UIViewController?

    public func presentPairingUI() {
        guard let viewController = topViewController() else {
            print("[WifiAwarePairingCoordinator] No view controller available to present pairing UI")
            return
        }

        if let existing = currentPairingController, existing.presentingViewController != nil {
            print("[WifiAwarePairingCoordinator] Pairing UI already presented")
            return
        }

        let pairingController = WifiAwarePairingController.createPairingViewController()
        currentPairingController = pairingController
        DispatchQueue.main.async {
            viewController.present(pairingController, animated: true)
        }
    }

    private func topViewController() -> UIViewController? {
        guard let windowScene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first,
              let rootViewController = windowScene.windows.first?.rootViewController else {
            return nil
        }

        var topController = rootViewController
        while let presented = topController.presentedViewController {
            topController = presented
        }
        return topController
    }
}
