import CoreGraphics
import Foundation
import ImageIO
import Kingfisher

/// An opaque PNG of `width` × `height` pixels.
func pngData(width: Int, height: Int) -> Data {
  let context = CGContext(
    data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue,
  )!
  context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
  context.fill(CGRect(x: 0, y: 0, width: width, height: height))
  let data = NSMutableData()
  let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil)!
  CGImageDestinationAddImage(destination, context.makeImage()!, nil)
  CGImageDestinationFinalize(destination)
  return data as Data
}

extension KFCrossPlatformImage {
  var pixelSize: CGSize? {
    #if canImport(UIKit)
      let image = cgImage
    #else
      let image = cgImage(forProposedRect: nil, context: nil, hints: nil)
    #endif
    return image.map { CGSize(width: $0.width, height: $0.height) }
  }
}
