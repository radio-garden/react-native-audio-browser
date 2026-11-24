import CoreGraphics

/// Where an image goes on a canvas the exact size of a CarPlay image slot. CarPlay sizes a
/// list or tile image by the UIImage's point size, so an image of any other size shows at
/// another size than its neighbours.
enum SlotFit {
  /// The image aspect-fitted to `slot` and centred, scaled up or down; nil when the image has
  /// no usable aspect ratio.
  static func drawRect(for imageSize: CGSize, in slot: CGSize) -> CGRect? {
    guard let fitted = imageSize.aspectFitted(in: slot) else { return nil }
    return CGRect(
      x: (slot.width - fitted.width) / 2,
      y: (slot.height - fitted.height) / 2,
      width: fitted.width,
      height: fitted.height,
    )
  }
}
