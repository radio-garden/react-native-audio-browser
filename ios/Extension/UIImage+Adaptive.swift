#if canImport(UIKit)
  import UIKit

  extension UIImage {
    /// One image that shows `light` or `dark` by the appearance of the trait collection it is
    /// displayed in, starting from `traits`; an appearance change re-resolves it in place.
    static func adaptive(light: UIImage, dark: UIImage, for traits: UITraitCollection) -> UIImage {
      let asset = UIImageAsset()
      // Registered without a display scale, the asset hands back each image at scale 1, its
      // pixels as points.
      asset.register(light, with: registration(.light, scale: light.scale))
      asset.register(dark, with: registration(.dark, scale: dark.scale))
      return asset.image(with: traits)
    }

    private static func registration(_ style: UIUserInterfaceStyle, scale: CGFloat) -> UITraitCollection {
      UITraitCollection(traitsFrom: [UITraitCollection(userInterfaceStyle: style), UITraitCollection(displayScale: scale)])
    }
  }
#endif
