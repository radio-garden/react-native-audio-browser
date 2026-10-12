import CarPlay
import Foundation
import os.log

/// The CarPlay scene's controller, exposed to Objective-C for `RNABCarPlaySceneDelegate`. It
/// shows a loading root until the player and a configured browser exist, then runs a
/// `CarPlaySession`; `stop()` ends it.
@MainActor
@objc(RNABCarPlayController)
public final class RNABCarPlayController: NSObject {
  /// The Siri handlers `RNABAudioBrowser.m` looks up by name, referenced so a static link
  /// keeps them.
  @objc public static let siriIntentHandlerClasses: [AnyClass] = [
    RNABMediaIntentHandler.self, RNABMediaAffinityHandler.self, RNABMediaAddHandler.self,
  ]

  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayController")
  private let interfaceController: CPInterfaceController
  private var interfaceDelegate: InterfaceControllerDelegate?
  private var instanceSubscription: Subscription?

  /// The pending or running start; nil while stopped.
  private var run: Task<Void, Never>?
  private(set) var session: CarPlaySession?

  @objc
  public init(interfaceController: CPInterfaceController) {
    self.interfaceController = interfaceController
    super.init()
  }

  /// Reports the scene's connection (JS `isCarPlayConnected`). Called on scene connect and
  /// disconnect, not from `start()`/`stop()`, which also cycle on a JS reload.
  @objc
  public static func setConnected(_ connected: Bool) {
    HybridAudioBrowser.setCarPlayConnected(connected)
  }

  @objc
  public func start() {
    guard run == nil else { return }
    logger.info("Starting CarPlay controller")

    let delegate = InterfaceControllerDelegate(controller: self)
    interfaceDelegate = delegate
    interfaceController.delegate = delegate

    // A JS reload replaces the bridge, and with it every emitter the session listens to.
    instanceSubscription = HybridAudioBrowser.instanceChangedEmitter.subscribeOnMain { [weak self] in
      self?.restart()
    }

    // Before the config loads, the app's loading title isn't known: the spinner only.
    interfaceController.safeSetRoot(CPListTemplate.loading(title: nil, path: nil, loadingTitle: nil), animated: false)

    run = Task { [weak self] in
      guard let (browser, player) = await playerAndConfiguredBrowser.wait(), !Task.isCancelled,
            let self
      else { return }
      let session = CarPlaySession(
        interfaceController: interfaceController, audioBrowser: browser, player: player,
      )
      self.session = session
      session.start()
    }
  }

  @objc
  public func stop() {
    guard run != nil else { return }
    logger.info("Stopping CarPlay controller")
    run?.cancel()
    run = nil
    session?.end()
    session = nil
    instanceSubscription = nil
  }

  private func restart() {
    guard run != nil else { return }
    logger.info("AudioBrowser instance changed — restarting CarPlay controller")
    stop()
    start()
  }
}

/// Keeps `CPInterfaceControllerDelegate` out of the Objective-C header.
private final class InterfaceControllerDelegate: NSObject, CPInterfaceControllerDelegate {
  private weak var controller: RNABCarPlayController?

  init(controller: RNABCarPlayController) {
    self.controller = controller
    super.init()
  }

  func templateDidAppear(_ aTemplate: CPTemplate, animated _: Bool) {
    controller?.session?.templateDidAppear(aTemplate)
  }

  func templateDidDisappear(_ aTemplate: CPTemplate, animated _: Bool) {
    controller?.session?.templateDidDisappear(aTemplate)
  }
}
