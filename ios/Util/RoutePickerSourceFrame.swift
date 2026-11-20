import CoreGraphics

/// The frame, in window coordinates, of the route picker's source view, which the picker points
/// at: the anchor, at least 1x1 since a source view with no area suppresses the picker, else 1x1
/// at the window's top-left corner.
enum RoutePickerSourceFrame {
  static func inWindow(anchor: CGRect?) -> CGRect {
    guard let anchor else { return CGRect(x: 0, y: 0, width: 1, height: 1) }
    let rect = anchor.standardized
    return CGRect(
      x: rect.minX,
      y: rect.minY,
      width: max(rect.width, 1),
      height: max(rect.height, 1),
    )
  }
}
