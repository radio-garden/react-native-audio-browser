/// The image to show for a light and dark pair as its halves load: both together, or one alone
/// once the other has failed.
struct ArtworkPair<Image> {
  private enum Half {
    case loading
    case failed
    case loaded(Image)
  }

  private var lightHalf = Half.loading
  private var darkHalf = Half.loading
  private let combine: (_ light: Image, _ dark: Image) -> Image

  init(combine: @escaping (_ light: Image, _ dark: Image) -> Image) {
    self.combine = combine
  }

  /// Records the light half, nil for a failed load; returns the image to show now, if any.
  mutating func light(_ image: Image?) -> Image? {
    lightHalf = image.map { .loaded($0) } ?? .failed
    return shown
  }

  /// Records the dark half, nil for a failed load; returns the image to show now, if any.
  mutating func dark(_ image: Image?) -> Image? {
    darkHalf = image.map { .loaded($0) } ?? .failed
    return shown
  }

  private var shown: Image? {
    switch (lightHalf, darkHalf) {
    case let (.loaded(light), .loaded(dark)): combine(light, dark)
    case let (.loaded(light), .failed): light
    case let (.failed, .loaded(dark)): dark
    default: nil
    }
  }
}
