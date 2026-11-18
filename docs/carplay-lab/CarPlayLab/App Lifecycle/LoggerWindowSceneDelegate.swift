/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
`LoggerWindowSceneDelegate` is the delegate for the `UIWindowScene` on the phone's display.
*/

import UIKit
import SwiftUI
import MediaPlayer

/// `LoggerWindowSceneDelegate` is the UIWindowScenDelegate
class LoggerWindowSceneDelegate: NSObject, UIWindowSceneDelegate {
    
    internal var window: UIWindow?
    
    // MARK: UISceneDelegate
    
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene, session.configuration.name == "LoggerSceneConfiguration" else { return }
        
        window = UIWindow(frame: windowScene.coordinateSpace.bounds)
        
        let loggerController = LoggerViewController()
        MemoryLogger.shared.delegate = loggerController
        
        let hostingController = UIHostingController(rootView: PlayerController())
        hostingController.title = "Music Player"
        
        let tabController = UITabBarController()
        tabController.addChild(loggerController)
        tabController.addChild(hostingController)
        
        window?.rootViewController = tabController
        window?.windowScene = windowScene
        window?.makeKeyAndVisible()
        
        MemoryLogger.shared.appendEvent("Logger window scene will connect.")
    }
    
    func sceneDidDisconnect(_ scene: UIScene) {
        if scene.session.configuration.name == "LoggerSceneConfiguration" {
            MemoryLogger.shared.appendEvent("Logger window scene did disconnect.")
        }
    }
    
    func sceneDidBecomeActive(_ scene: UIScene) {
        if scene.session.configuration.name == "LoggerSceneConfiguration" {
            MemoryLogger.shared.appendEvent("Logger window scene did become active.")
        }
    }
    
    func sceneWillResignActive(_ scene: UIScene) {
        if scene.session.configuration.name == "LoggerSceneConfiguration" {
            MemoryLogger.shared.appendEvent("Logger window scene will resign active.")
        }
    }
}
