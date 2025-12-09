import CarPlay
import UIKit
import React
import React_RCTAppDelegate
import ReactAppDependencyProvider

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  var reactNativeDelegate: ReactNativeDelegate?
  var reactNativeFactory: RCTReactNativeFactory?

  /// Track if React Native has been started (for headless CarPlay mode)
  private var reactNativeStarted = false

  /// Hidden window for headless React Native (CarPlay standalone mode)
  private var headlessWindow: UIWindow?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    let delegate = ReactNativeDelegate()
    let factory = RCTReactNativeFactory(delegate: delegate)
    delegate.dependencyProvider = RCTAppDependencyProvider()

    reactNativeDelegate = delegate
    reactNativeFactory = factory

    // Note: Window creation and React Native startup is handled by PhoneSceneDelegate
    // when using scene-based lifecycle (required for CarPlay support)

    return true
  }

  /// Start React Native in headless mode (for CarPlay when phone app isn't running)
  /// This creates a hidden window to host React Native without displaying UI
  @objc func startReactNativeHeadless() {
    guard !reactNativeStarted else { return }
    reactNativeStarted = true

    // Create a hidden window to host React Native
    // This is needed because RCTReactNativeFactory requires a window
    // Store as property to prevent deallocation
    headlessWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
    headlessWindow?.isHidden = true

    reactNativeFactory?.startReactNative(
      withModuleName: "AudioBrowserExample",
      in: headlessWindow,
      launchOptions: nil
    )
  }

  /// Mark React Native as started (called from PhoneSceneDelegate)
  func markReactNativeStarted() {
    reactNativeStarted = true
  }

  // MARK: - CarPlay Scene Configuration

  func application(
    _ application: UIApplication,
    configurationForConnecting connectingSceneSession: UISceneSession,
    options: UIScene.ConnectionOptions
  ) -> UISceneConfiguration {
    if connectingSceneSession.role == UISceneSession.Role.carTemplateApplication {
      let config = UISceneConfiguration(name: "CarPlay", sessionRole: connectingSceneSession.role)
      // Use the Objective-C CarPlay scene delegate from the library
      config.delegateClass = NSClassFromString("RNABCarPlaySceneDelegate") as? UIResponder.Type
      return config
    } else {
      // Phone scene
      let config = UISceneConfiguration(name: "Phone", sessionRole: connectingSceneSession.role)
      config.delegateClass = PhoneSceneDelegate.self
      return config
    }
  }
}

class ReactNativeDelegate: RCTDefaultReactNativeFactoryDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? {
    self.bundleURL()
  }

  override func bundleURL() -> URL? {
#if DEBUG
    RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
#else
    Bundle.main.url(forResource: "main", withExtension: "jsbundle")
#endif
  }
}
