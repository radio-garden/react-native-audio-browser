#if canImport(CarPlay) && !targetEnvironment(macCatalyst)
import CarPlay
import SDWebImage

@available(iOS 14.0, *)
extension CPListItem {
  /// Loads and sets an image from a URL using SDWebImage for caching.
  /// - Parameters:
  ///   - url: The URL to load the image from
  ///   - placeholderImage: Optional placeholder to show while loading
  func sd_setImage(with url: URL?, placeholderImage: UIImage? = nil) {
    guard let url else {
      setImage(placeholderImage)
      return
    }

    SDWebImageManager.shared.loadImage(
      with: url,
      options: [.retryFailed],
      progress: nil
    ) { [weak self] image, _, _, _, _, _ in
      DispatchQueue.main.async {
        self?.setImage(image ?? placeholderImage)
      }
    }
  }
}
#endif
