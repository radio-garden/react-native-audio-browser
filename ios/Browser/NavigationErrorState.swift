import NitroModules

/// The browser's last navigation error and the display formatted for it, as JS sees them. A
/// newer report or a clear supersedes a display still being formatted.
@MainActor
final class NavigationErrorState {
  private(set) var error: NavigationError? {
    didSet { if oldValue != nil || error != nil { onErrorChanged(error) } }
  }

  private(set) var formatted: FormattedNavigationError? {
    didSet { if oldValue != nil || formatted != nil { onFormattedChanged(formatted) } }
  }

  private var generation = 0
  private let onErrorChanged: (NavigationError?) -> Void
  private let onFormattedChanged: (FormattedNavigationError?) -> Void

  init(
    onErrorChanged: @escaping (NavigationError?) -> Void,
    onFormattedChanged: @escaping (FormattedNavigationError?) -> Void,
  ) {
    self.onErrorChanged = onErrorChanged
    self.onFormattedChanged = onFormattedChanged
  }

  func clear() {
    generation &+= 1
    error = nil
    formatted = nil
  }

  func report(_ error: NavigationError, format: (NavigationError) async -> FormattedNavigationError) async {
    generation &+= 1
    let reported = generation
    self.error = error
    let display = await format(error)
    guard reported == generation else { return }
    formatted = display
  }
}
