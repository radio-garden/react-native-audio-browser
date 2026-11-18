import os.log
import UIKit

/// Renders SF Symbols to PNG files in the caches directory, as `file://` URIs that
/// React Native's `<Image>` and native image loaders both accept.
///
/// Artwork format: `sf:symbol.name?bg=#RRGGBB&fg=#RRGGBB` (3- or 6-digit hex).
/// `bg` defaults to transparent, `fg` to black.
final class SFSymbolRenderer: @unchecked Sendable {
  @MainActor static let shared = SFSymbolRenderer()
  static let defaultCanvasSize = CGSize(width: 200, height: 200)

  // Part of the cache filename: bump it whenever the rendering changes. The caches
  // directory survives app updates and the existence check is the only validation,
  // so without a bump devices keep serving the old pixels.
  private static let renderVersion = 1

  private let logger = Logger(subsystem: "com.audiobrowser", category: "SFSymbolRenderer")
  private let scale: CGFloat
  private let cacheDirectory: URL

  @MainActor
  private init() {
    scale = appDisplayScale

    let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    cacheDirectory = caches.appendingPathComponent("sf-symbols", isDirectory: true)
    try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
  }

  /// Returns the `file://` URI of the PNG for `artworkValue` (with or without the `sf:`
  /// prefix), rendering it only when no file for it exists yet. `canvasSize` is in points,
  /// drawn at device scale. Nil when there is no such symbol or the file can't be written.
  func render(_ artworkValue: String, canvasSize: CGSize = defaultCanvasSize) -> String? {
    let safeArtwork = artworkValue.unicodeScalars
      .filter { CharacterSet.alphanumerics.contains($0) || $0 == "_" }
      .map(String.init).joined()
    let fileName =
      "\(safeArtwork)_\(Int(canvasSize.width))x\(Int(canvasSize.height))_\(Int(scale))x_v\(Self.renderVersion)"
    let fileURL = cacheDirectory.appendingPathComponent("\(fileName).png")

    if FileManager.default.fileExists(atPath: fileURL.path) {
      return fileURL.absoluteString
    }

    let (symbolName, backgroundColor, symbolColor) = Self.parseArtwork(artworkValue)
    guard let pngData = Self.image(
      name: symbolName, canvasSize: canvasSize, scale: scale,
      tint: symbolColor ?? .black, background: backgroundColor,
    )?.pngData() else { return nil }

    do {
      try pngData.write(to: fileURL, options: .atomic)
    } catch {
      logger.error("Failed to write SF Symbol PNG: \(error.localizedDescription)")
      return nil
    }

    return fileURL.absoluteString
  }

  /// Draws the symbol at 45% of the canvas, centred and tinted, over `background` (nil leaves it
  /// transparent). Nil when there is no symbol of that name.
  static func image(
    name: String,
    canvasSize: CGSize,
    scale: CGFloat,
    tint: UIColor,
    background: UIColor?,
  ) -> UIImage? {
    let pointSize = min(canvasSize.width, canvasSize.height) * 0.45
    let config = UIImage.SymbolConfiguration(pointSize: pointSize, weight: .medium)
    guard let symbol = UIImage(systemName: name, withConfiguration: config) else { return nil }
    let format = UIGraphicsImageRendererFormat()
    format.scale = scale
    return UIGraphicsImageRenderer(size: canvasSize, format: format).image { _ in
      if let background {
        background.setFill()
        UIRectFill(CGRect(origin: .zero, size: canvasSize))
      }
      let size = symbol.size
      tint.set()
      symbol.withRenderingMode(.alwaysTemplate).draw(in: CGRect(
        x: (canvasSize.width - size.width) / 2,
        y: (canvasSize.height - size.height) / 2,
        width: size.width,
        height: size.height,
      ))
    }.withRenderingMode(.alwaysOriginal) // Keeps the drawn tint inside a tinted view.
  }

  static func isSFSymbol(_ artwork: String) -> Bool {
    artwork.hasPrefix("sf:")
  }

  /// `"sf:heart.fill?bg=#000&fg=#fff"` → `("heart.fill", black, white)`; a colour that is
  /// absent is nil, one that is not 3 or 6 characters long is black.
  static func parseArtwork(_ value: String) -> (symbolName: String, backgroundColor: UIColor?, symbolColor: UIColor?) {
    let (symbolName, params) = parseArtworkValue(value)
    let bg = params["bg"].map { UIColor(hex: $0) }
    let fg = params["fg"].map { UIColor(hex: $0) }
    return (symbolName, bg, fg)
  }

  private static func parseArtworkValue(_ value: String) -> (symbolName: String, params: [String: String]) {
    let stripped = value.hasPrefix("sf:") ? String(value.dropFirst(3)) : value
    guard let questionMark = stripped.firstIndex(of: "?") else {
      return (stripped, [:])
    }
    let symbolName = String(stripped[..<questionMark])
    let queryString = String(stripped[stripped.index(after: questionMark)...])
    var params: [String: String] = [:]
    for pair in queryString.split(separator: "&") {
      let parts = pair.split(separator: "=", maxSplits: 1)
      guard parts.count == 2 else { continue }
      let key = String(parts[0])
      let val = String(parts[1]).replacingOccurrences(of: "#", with: "")
      params[key] = val
    }
    return (symbolName, params)
  }
}

extension UIColor {
  /// `#` optional. Any length but 3 or 6 is black; the digits are not validated
  /// (`init(declaredHex:)` does that).
  convenience init(hex: String) {
    let hex = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
    var rgb: UInt64 = 0
    Scanner(string: hex).scanHexInt64(&rgb)

    switch hex.count {
    case 3:
      let r = CGFloat((rgb >> 8) & 0xF) / 15
      let g = CGFloat((rgb >> 4) & 0xF) / 15
      let b = CGFloat(rgb & 0xF) / 15
      self.init(red: r, green: g, blue: b, alpha: 1)
    case 6:
      let r = CGFloat((rgb >> 16) & 0xFF) / 255
      let g = CGFloat((rgb >> 8) & 0xFF) / 255
      let b = CGFloat(rgb & 0xFF) / 255
      self.init(red: r, green: g, blue: b, alpha: 1)
    default:
      self.init(white: 0, alpha: 1)
    }
  }

  /// Nil unless 3 or 6 hex digits (`#` optional), so an unparseable declared colour
  /// (`style.cardTint`) falls back to the system default instead of black.
  convenience init?(declaredHex hex: String) {
    let digits = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
    guard [3, 6].contains(digits.count),
          digits.allSatisfy(\.isHexDigit)
    else { return nil }
    self.init(hex: hex)
  }
}
