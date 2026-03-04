import os.log
import UIKit

/// Renders SF Symbols to PNG files in the caches directory.
/// Returns `file://` URIs usable by both React Native's `<Image>` and native image loaders.
///
/// Artwork format: `sf:symbol.name?bg=#RRGGBB&fg=#RRGGBB`
/// - `bg`: background color (default: transparent)
/// - `fg`: foreground/symbol color (default: #000000)
final class SFSymbolRenderer: @unchecked Sendable {
  @MainActor static let shared = SFSymbolRenderer()
  static let defaultCanvasSize = CGSize(width: 200, height: 200)

  private let logger = Logger(subsystem: "com.audiobrowser", category: "SFSymbolRenderer")
  private let scale: CGFloat
  private let cacheDirectory: URL

  @MainActor
  private init() {
    scale = UIScreen.main.scale

    let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    cacheDirectory = caches.appendingPathComponent("sf-symbols", isDirectory: true)
    try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
  }

  /// Renders an SF Symbol to a PNG file and returns its `file://` URI.
  ///
  /// Skips rendering if the file already exists on disk.
  ///
  /// - Parameters:
  ///   - artworkValue: The artwork string (e.g. "sf:heart.fill?bg=#000&fg=#fff" or "heart.fill")
  ///   - canvasSize: The canvas size in points (rendered at device scale)
  /// - Returns: A file URI string, or nil if the symbol doesn't exist or rendering fails
  func render(_ artworkValue: String, canvasSize: CGSize = defaultCanvasSize) -> String? {
    let (symbolName, backgroundColor, symbolColor) = Self.parseArtwork(artworkValue)

    // Validate symbol exists before any filesystem work
    let symbolPointSize = min(canvasSize.width, canvasSize.height) * 0.45
    let config = UIImage.SymbolConfiguration(pointSize: symbolPointSize, weight: .medium)
    guard let symbol = UIImage(systemName: symbolName, withConfiguration: config) else {
      return nil
    }

    let safeArtwork = artworkValue.unicodeScalars
      .filter { CharacterSet.alphanumerics.contains($0) || $0 == "_" }
      .map(String.init).joined()
    let fileName = "\(safeArtwork)_\(Int(canvasSize.width))x\(Int(canvasSize.height))_\(Int(scale))x"
    let fileURL = cacheDirectory.appendingPathComponent("\(fileName).png")

    if FileManager.default.fileExists(atPath: fileURL.path) {
      return fileURL.absoluteString
    }

    let renderer = UIGraphicsImageRenderer(size: canvasSize, format: {
      let fmt = UIGraphicsImageRendererFormat()
      fmt.scale = scale
      return fmt
    }())

    let pngData = renderer.pngData { context in
      if let backgroundColor {
        backgroundColor.setFill()
        UIRectFill(CGRect(origin: .zero, size: canvasSize))
      }

      let symbolSize = symbol.size
      let x = (canvasSize.width - symbolSize.width) / 2
      let y = (canvasSize.height - symbolSize.height) / 2

      (symbolColor ?? .black).set()
      symbol.withRenderingMode(.alwaysTemplate)
        .draw(in: CGRect(x: x, y: y, width: symbolSize.width, height: symbolSize.height))
    }

    do {
      try pngData.write(to: fileURL, options: .atomic)
    } catch {
      logger.error("Failed to write SF Symbol PNG: \(error.localizedDescription)")
      return nil
    }

    return fileURL.absoluteString
  }

  /// Whether the given artwork string is an SF Symbol reference.
  static func isSFSymbol(_ artwork: String) -> Bool {
    artwork.hasPrefix("sf:")
  }

  /// Parses an SF Symbol artwork string into symbol name and resolved colors.
  /// `"sf:heart.fill?bg=#000&fg=#fff"` → `("heart.fill", bg: black, fg: white)`
  static func parseArtwork(_ value: String) -> (symbolName: String, backgroundColor: UIColor?, symbolColor: UIColor?) {
    let (symbolName, params) = parseArtworkValue(value)
    let bg = params["bg"].map { UIColor(hex: $0) }
    let fg = params["fg"].map { UIColor(hex: $0) }
    return (symbolName, bg, fg)
  }

  /// Parses an SF Symbol artwork string, stripping the `sf:` prefix if present.
  /// `"sf:heart.fill?bg=#000&fg=#fff"` → `("heart.fill", ["bg": "000", "fg": "fff"])`
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
}
