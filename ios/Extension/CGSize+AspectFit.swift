import CoreGraphics

extension CGSize {
  /// The largest size with this size's aspect ratio that fits inside `bounds`; `bounds` itself
  /// when this size has no usable aspect ratio (a zero, negative or non-finite side).
  func aspectFitted(in bounds: CGSize) -> CGSize {
    guard width > 0, height > 0, width.isFinite, height.isFinite else { return bounds }
    let factor = min(bounds.width / width, bounds.height / height)
    return CGSize(width: width * factor, height: height * factor)
  }
}
