import AVKit
import os.log
import UIKit

/// Presents the system audio route picker over the topmost view controller.
@MainActor
enum OutputPicker {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "OutputPicker")

  static func present() {
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
    let routePicker = AVRoutePickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
    routePicker.alpha = 0.01
    topController.view.addSubview(routePicker)

    if let button = firstButton(in: routePicker) {
      button.sendActions(for: .touchUpInside)
    } else {
      logger.error("openOutputPicker: no UIButton found inside AVRoutePickerView")
    }

    // On iPad the picker's popover anchors to this view.
    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
      routePicker.removeFromSuperview()
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
