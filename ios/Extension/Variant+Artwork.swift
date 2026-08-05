import Foundation

/// Reading the `artwork` union, which is either one URL or a URL per appearance.
///
/// Nitro surfaces it as `.first(String)` / `.second(ArtworkVariants)`. Call
/// sites want one of two things — the URL to display, or both URLs so a
/// `UIImageAsset` can adapt without re-fetching — so those are the only
/// accessors here, and nothing below the boundary repeats the unwrap.
///
/// Deliberately Foundation-only: this is part of the SPM testable target, which
/// also builds for macOS, so it cannot reach for `UIUserInterfaceStyle` to name
/// an appearance. Picking between the two URLs is CarPlay's job anyway — it
/// registers both and lets UIKit choose.
extension Variant_String_ArtworkVariants {
  /// Both URLs, when the track ships a pair; `nil` for a single URL, which
  /// needs no per-appearance handling.
  var variants: ArtworkVariants? {
    switch self {
    case .first: nil
    case let .second(variants): variants
    }
  }

  /// The URL to use where appearance is unknown or does not apply — now-playing
  /// metadata, JS-facing values, the artwork transform pipeline.
  ///
  /// Resolves a pair to its dark URL. CarPlay dark is the common case, Android
  /// Auto is dark-only, and it is what a single-URL track would have shipped
  /// anyway — so a caller that cannot express appearance gets the same image it
  /// got before pairs existed.
  var url: String {
    switch self {
    case let .first(url): url
    case let .second(variants): variants.dark
    }
  }
}

extension Variant_String_ArtworkVariants: Equatable {
  /// Needed by `TabBarEntries.same`, and by the synthesised `Track: Equatable`
  /// in the test stubs; the generated enum declares no conformance itself.
  public static func == (lhs: Self, rhs: Self) -> Bool {
    switch (lhs, rhs) {
    case let (.first(l), .first(r)): l == r
    case let (.second(l), .second(r)): l.light == r.light && l.dark == r.dark
    default: false
    }
  }
}
