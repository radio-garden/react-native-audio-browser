import Foundation

enum ArtworkHalf: Hashable {
  /// The one image of a slot that has no light and dark pair.
  case only
  case light
  case dark
}

enum ArtworkSlotLoader {
  /// Loads one slot's image, or the two halves of its light and dark pair at once, retrying each
  /// on `waits`, and hands `apply` each image to show (`ArtworkPair`). Nothing is applied once
  /// the task is cancelled. Returns whether any image was applied.
  /// - Parameter attempt: One whole load of a half; nil when it has nothing to load.
  @MainActor
  static func load<Image>(
    pair: Bool,
    waits: ArtworkRetryWaits?,
    attempt: @escaping @MainActor (ArtworkHalf) async -> Result<Image, ArtworkLoadFailure>?,
    combine: @escaping (_ light: Image, _ dark: Image) -> Image,
    apply: @escaping @MainActor (Image) -> Void,
  ) async -> Bool {
    let applied = Shared(false)
    let show: @MainActor (Image?) -> Void = { image in
      guard let image, !Task.isCancelled else { return }
      applied.value = true
      apply(image)
    }
    guard pair else {
      if case let .success(image) = await load(.only, waits: waits, attempt: attempt, onFailure: {}) {
        show(image)
      }
      return applied.value
    }
    let halves = Shared(ArtworkPair(combine: combine))
    async let light: Void = loadHalf(.light, waits: waits, attempt: attempt) { show(halves.value.light($0)) }
    async let dark: Void = loadHalf(.dark, waits: waits, attempt: attempt) { show(halves.value.dark($0)) }
    _ = await (light, dark)
    return applied.value
  }

  /// Hands `record` nil at the half's first failure, and its image once it loads.
  @MainActor
  private static func loadHalf<Image>(
    _ half: ArtworkHalf,
    waits: ArtworkRetryWaits?,
    attempt: @escaping @MainActor (ArtworkHalf) async -> Result<Image, ArtworkLoadFailure>?,
    record: @escaping @MainActor (Image?) -> Void,
  ) async {
    var failed = false
    let result = await load(half, waits: waits, attempt: attempt) {
      guard !failed else { return }
      failed = true
      record(nil)
    }
    if case let .success(image) = result { record(image) }
  }

  @MainActor
  private static func load<Image>(
    _ half: ArtworkHalf,
    waits: ArtworkRetryWaits?,
    attempt: @escaping @MainActor (ArtworkHalf) async -> Result<Image, ArtworkLoadFailure>?,
    onFailure: @escaping @MainActor () -> Void,
  ) async -> Result<Image, ArtworkLoadFailure> {
    await ArtworkRetry.load(waits: waits, onFailure: { _ in onFailure() }) {
      await attempt(half) ?? .failure(nothingToLoad)
    }
  }

  /// A final failure, so a half with nothing to load is not retried.
  private static let nothingToLoad = ArtworkLoadFailure(detail: "nothing to load")
}

@MainActor
private final class Shared<Value> {
  var value: Value

  init(_ value: Value) { self.value = value }
}
