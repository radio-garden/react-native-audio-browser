import Foundation

struct MediaURL {
  let value: URL
  let isLocal: Bool
  private let originalObject: Any

  init?(object: Any?) {
    guard let object else { return nil }
    originalObject = object

    // This is based on logic found in RCTConvert NSURLRequest
    if let localObject = object as? [String: Any] {
      var urlString = localObject["uri"] as? String ?? localObject["url"] as! String

      if let bundleName = localObject["bundle"] as? String {
        urlString = String(format: "%@.bundle/%@", bundleName, urlString)
      }

      isLocal = !urlString.lowercased().hasPrefix("http")
      guard let converted = Self.convertToURL(urlString) else { return nil }
      value = converted
    } else {
      let urlString = object as! String
      isLocal = urlString.lowercased().hasPrefix("file://")
      guard let converted = Self.convertToURL(urlString) else { return nil }
      value = converted
    }
  }

  /// Convert a string to URL, matching RCTConvert NSURL behavior
  private static func convertToURL(_ string: String) -> URL? {
    // Try as absolute URL first
    if let url = URL(string: string), url.scheme != nil {
      return url
    }

    // If it has a scheme but failed, try percent-encoding
    if string.contains("://") {
      var allowed = CharacterSet()
      allowed.formUnion(.urlUserAllowed)
      allowed.formUnion(.urlPasswordAllowed)
      allowed.formUnion(.urlHostAllowed)
      allowed.formUnion(.urlPathAllowed)
      allowed.formUnion(.urlQueryAllowed)
      allowed.formUnion(.urlFragmentAllowed)
      if let encoded = string.addingPercentEncoding(withAllowedCharacters: allowed),
         let url = URL(string: encoded) {
        return url
      }
    }

    // Assume it's a local path
    var path = string.removingPercentEncoding ?? string

    if path.hasPrefix("~") {
      // Expand tilde for user directory
      path = NSString(string: path).expandingTildeInPath
    } else if !path.hasPrefix("/") {
      // Not absolute - assume it's a bundle resource path
      path = (Bundle.main.resourcePath ?? "") + "/" + path
    }

    return URL(fileURLWithPath: path)
  }
}
