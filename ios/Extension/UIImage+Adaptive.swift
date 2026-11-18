import UIKit

extension UIImage {
  /// One image that shows `light` or `dark` by the appearance of the trait collection it is
  /// displayed in, starting from `traits`; an appearance change re-resolves it in place.
  static func adaptive(light: UIImage, dark: UIImage, for traits: UITraitCollection) -> UIImage {
    let asset = UIImageAsset()
    asset.register(light, with: UITraitCollection(userInterfaceStyle: .light))
    asset.register(dark, with: UITraitCollection(userInterfaceStyle: .dark))
    return asset.image(with: traits)
  }
}
