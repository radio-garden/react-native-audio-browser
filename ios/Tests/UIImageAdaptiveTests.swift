#if canImport(UIKit)
  import Testing
  import UIKit

  @testable import AudioBrowserTestable

  @Suite("UIImage.adaptive(light:dark:for:)")
  @MainActor
  struct UIImageAdaptiveTests {
    private func image(points: CGFloat, scale: CGFloat) -> UIImage {
      let format = UIGraphicsImageRendererFormat()
      format.scale = scale
      return UIGraphicsImageRenderer(size: CGSize(width: points, height: points), format: format).image { _ in }
    }

    private func traits(scale: CGFloat, style: UIUserInterfaceStyle) -> UITraitCollection {
      UITraitCollection(traitsFrom: [UITraitCollection(displayScale: scale), UITraitCollection(userInterfaceStyle: style)])
    }

    @Test(arguments: [2.0, 3.0])
    func thePairKeepsTheSizeAndScaleOfItsImages(scale: CGFloat) {
      let pair = UIImage.adaptive(
        light: image(points: 44, scale: scale),
        dark: image(points: 44, scale: scale),
        for: traits(scale: scale, style: .dark),
      )
      #expect(pair.size == CGSize(width: 44, height: 44))
      #expect(pair.scale == scale)
    }

    @Test func thePairResolvesToTheOtherImageInTheOtherAppearance() throws {
      let light = image(points: 44, scale: 2)
      let dark = image(points: 30, scale: 2)
      let pair = UIImage.adaptive(light: light, dark: dark, for: traits(scale: 2, style: .dark))
      let resolved = try #require(pair.imageAsset?.image(with: traits(scale: 2, style: .light)))
      #expect(pair.size == dark.size)
      #expect(resolved.size == light.size)
    }
  }
#endif
