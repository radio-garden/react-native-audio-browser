import Foundation
import UIKit

class PhoneSceneDelegate: UIResponder, UIWindowSceneDelegate {
  var window: UIWindow?

  func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
    guard session.role == .windowApplication else { return }
    guard let appDelegate = UIApplication.shared.delegate as? AppDelegate else { return }
    guard let windowScene = scene as? UIWindowScene else { return }

    let window = UIWindow(windowScene: windowScene)
    appDelegate.window = window

    // Mark that React Native will be started (prevents headless startup)
    appDelegate.markReactNativeStarted()

    // Start React Native in this window
    appDelegate.reactNativeFactory?.startReactNative(
      withModuleName: "AudioBrowserExample",
      in: window,
      launchOptions: nil
    )

    self.window = window
  }
}
