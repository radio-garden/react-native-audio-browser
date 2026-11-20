import AVKit
import os.log
import UIKit

/// Presents the system audio route picker over the topmost view controller from a source view at
/// `anchor`, a rect in the key window's coordinates, or at the window's top-left corner without one.
@MainActor
enum OutputPicker {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "OutputPicker")
  private static let delegate = SourceRemovingDelegate()

  static func present(anchor: CGRect?) {
    guard let windowScene = UIApplication.shared.activeWindowScene,
          let window = windowScene.windows.first(where: { $0.isKeyWindow }),
          var topController = window.rootViewController
    else {
      logger.error("openOutputPicker: no active window scene / root view controller")
      return
    }
    while let presented = topController.presentedViewController {
      topController = presented
    }

    // AVRoutePickerView has no API to present its picker, so its internal button is tapped. The
    // view must be visible to the system (a hidden source view suppresses the picker), and the
    // button can sit below the top-level subviews.
    let routePicker = AVRoutePickerView(
      frame: topController.view.convert(RoutePickerSourceFrame.inWindow(anchor: anchor), from: nil),
    )
    routePicker.alpha = 0.01
    // Sized to the anchor, the view would take the taps meant for what lies under it.
    routePicker.isUserInteractionEnabled = false
    routePicker.delegate = delegate
    topController.view.addSubview(routePicker)
    delegate.lifetime.added(routePicker)

    if let button = firstButton(in: routePicker) {
      button.sendActions(for: .touchUpInside)
    } else {
      logger.error("openOutputPicker: no UIButton found inside AVRoutePickerView")
    }

    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
      delegate.lifetime.settled(routePicker)
    }
  }

  private static func firstButton(in view: UIView) -> UIButton? {
    if let button = view as? UIButton { return button }
    for subview in view.subviews {
      if let button = firstButton(in: subview) { return button }
    }
    return nil
  }
}

@MainActor
private final class SourceRemovingDelegate: NSObject, @preconcurrency AVRoutePickerViewDelegate {
  let lifetime = RoutePickerSourceLifetime<AVRoutePickerView> { $0.removeFromSuperview() }

  func routePickerViewWillBeginPresentingRoutes(_ routePicker: AVRoutePickerView) {
    lifetime.willBeginPresenting(routePicker)
  }

  func routePickerViewDidEndPresentingRoutes(_ routePicker: AVRoutePickerView) {
    lifetime.didEndPresenting(routePicker)
  }
}
