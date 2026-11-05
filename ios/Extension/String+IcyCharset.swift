import Foundation

extension String {
  /// Re-decodes an ICY title that AVFoundation decoded as Latin-1, using the IANA `charset`
  /// the track declares. AVFoundation keeps no raw bytes, so a Latin-1 decode is recognised
  /// by its shape: every scalar at or below U+00FF and at least one at or above U+0080.
  /// Returns `self` for pure ASCII, a title with higher scalars (already decoded), an unknown
  /// charset, or bytes the charset cannot decode.
  func redecodingIcyTitle(charset: String) -> String {
    var high = false
    for scalar in unicodeScalars {
      if scalar.value > 0xFF { return self }
      if scalar.value >= 0x80 { high = true }
    }
    guard high, let data = data(using: .isoLatin1) else { return self }
    let cfEncoding = CFStringConvertIANACharSetNameToEncoding(charset as CFString)
    guard cfEncoding != kCFStringEncodingInvalidId else { return self }
    let encoding = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cfEncoding))
    return String(data: data, encoding: encoding) ?? self
  }
}
