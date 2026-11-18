import Foundation

// Foundation only: this file is in the SPM test target, which also builds for macOS, so it
// cannot use UIKit to pick an appearance. CarPlay registers both URLs and lets UIKit choose.
extension Variant_String_ArtworkVariants {
  /// Both URLs when the artwork is a light/dark pair; `nil` for a single URL.
  var variants: ArtworkVariants? {
    switch self {
    case .first: nil
    case let .second(variants): variants
    }
  }

  /// The URL for where appearance is unknown or does not apply (now-playing metadata, values
  /// sent to JS, the artwork transform). A pair resolves to its dark URL: CarPlay is usually
  /// dark, Android Auto is dark-only, and it is the image a single-URL track ships.
  var url: String {
    switch self {
    case let .first(url): url
    case let .second(variants): variants.dark
    }
  }
}

// The Nitro-generated enum declares no Equatable conformance; `TabBarEntries.same` and the
// test stubs' synthesised `Track: Equatable` need it.
extension Variant_String_ArtworkVariants: Equatable {
  public static func == (lhs: Self, rhs: Self) -> Bool {
    switch (lhs, rhs) {
    case let (.first(l), .first(r)): l == r
    case let (.second(l), .second(r)): l.light == r.light && l.dark == r.dark
    default: false
    }
  }
}
