import SwiftUI
import PhotosUI
import ComposeApp
import UIKit

/// Coordinator that bridges Swift's PHPickerViewController to Kotlin's ImagePickerBridge protocol.
///
/// Handles image selection, resizing, and compression before passing data back to Kotlin.
/// Conforms to the `ImagePickerBridge` protocol defined in Kotlin/ComposeApp.
@objc public class ImagePickerCoordinator: NSObject, ImagePickerBridge {
    
    // MARK: - Constants
    
    private static let maxImageDimension: CGFloat = 1024
    private static let jpegQuality: CGFloat = 0.85
    
    // MARK: - Properties
    
    private weak var presentingViewController: UIViewController?
    private var currentCallback: (any ImagePickerCallback)?
    
    // MARK: - Initialization
    
    public init(presentingViewController: UIViewController?) {
        self.presentingViewController = presentingViewController
        super.init()
    }
    
    // MARK: - ImagePickerBridge Protocol
    
    public func pickImage(callback: any ImagePickerCallback) {
        guard let viewController = presentingViewController ?? topViewController() else {
            print("[ImagePickerCoordinator] No view controller available to present picker")
            return
        }
        
        currentCallback = callback
        
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        configuration.preferredAssetRepresentationMode = .current
        
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = self
        
        DispatchQueue.main.async {
            viewController.present(picker, animated: true)
        }
    }
    
    public func isAvailable() -> Bool {
        return true // PHPickerViewController is available on iOS 14+
    }
    
    // MARK: - Private Helpers
    
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
    
    private func processImage(_ image: UIImage, filename: String) -> (Data, String, String)? {
        // Resize if needed
        let resizedImage = resizeImage(image)
        
        // Compress to JPEG
        guard let jpegData = resizedImage.jpegData(compressionQuality: Self.jpegQuality) else {
            print("[ImagePickerCoordinator] Failed to compress image to JPEG")
            return nil
        }
        
        // Ensure filename has .jpg extension
        var finalFilename = filename
        if !finalFilename.lowercased().hasSuffix(".jpg") && !finalFilename.lowercased().hasSuffix(".jpeg") {
            finalFilename = (finalFilename as NSString).deletingPathExtension + ".jpg"
        }
        
        print("[ImagePickerCoordinator] Processed image: \(finalFilename), \(jpegData.count) bytes")
        
        return (jpegData, finalFilename, "image/jpeg")
    }
    
    private func resizeImage(_ image: UIImage) -> UIImage {
        let width = image.size.width
        let height = image.size.height
        
        // No resize needed if within limits
        if width <= Self.maxImageDimension && height <= Self.maxImageDimension {
            return image
        }
        
        // Calculate new size maintaining aspect ratio
        let scale = Self.maxImageDimension / max(width, height)
        let newWidth = width * scale
        let newHeight = height * scale
        let newSize = CGSize(width: newWidth, height: newHeight)
        
        print("[ImagePickerCoordinator] Resizing image from \(Int(width))x\(Int(height)) to \(Int(newWidth))x\(Int(newHeight))")
        
        // Use UIGraphicsImageRenderer for efficient resizing
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}

// MARK: - PHPickerViewControllerDelegate

extension ImagePickerCoordinator: PHPickerViewControllerDelegate {
    public func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        
        guard let result = results.first else {
            print("[ImagePickerCoordinator] No image selected")
            currentCallback = nil
            return
        }
        
        let itemProvider = result.itemProvider
        
        // Get filename
        let filename = itemProvider.suggestedName ?? "image"
        
        // Check if we can load UIImage
        guard itemProvider.canLoadObject(ofClass: UIImage.self) else {
            print("[ImagePickerCoordinator] Cannot load UIImage from selected item")
            currentCallback = nil
            return
        }
        
        itemProvider.loadObject(ofClass: UIImage.self) { [weak self] object, error in
            guard let self = self else { return }
            
            if let error = error {
                print("[ImagePickerCoordinator] Error loading image: \(error.localizedDescription)")
                self.currentCallback = nil
                return
            }
            
            guard let image = object as? UIImage else {
                print("[ImagePickerCoordinator] Failed to cast loaded object to UIImage")
                self.currentCallback = nil
                return
            }
            
            // Process and deliver on main thread
            DispatchQueue.main.async {
                if let result = self.processImage(image, filename: filename) {
                    self.currentCallback?.onImagePicked(data: result.0, filename: result.1, mimeType: result.2)
                }
                self.currentCallback = nil
            }
        }
    }
}
