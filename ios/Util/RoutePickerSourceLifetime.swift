/// Decides when the source view of the system route picker leaves the window.
///
/// The picker closes when its source view leaves, so a source stays while its picker shows and
/// goes when the picker ends. A source whose picker has not begun showing by `settled(_:)` goes
/// then. A picker that begins but never ends (a simulator's) keeps its source until the next
/// source is added.
@MainActor
final class RoutePickerSourceLifetime<Source: AnyObject> {
  private let remove: (Source) -> Void
  private weak var current: Source?
  private weak var presenting: Source?

  init(remove: @escaping (Source) -> Void) {
    self.remove = remove
  }

  func added(_ source: Source) {
    if let current, current !== source { remove(current) }
    current = source
  }

  func willBeginPresenting(_ source: Source) {
    presenting = source
  }

  func didEndPresenting(_ source: Source) {
    if presenting === source { presenting = nil }
    remove(source)
  }

  func settled(_ source: Source) {
    if presenting !== source { remove(source) }
  }
}
