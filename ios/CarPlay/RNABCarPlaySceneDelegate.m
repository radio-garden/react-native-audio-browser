#import "RNABCarPlaySceneDelegate.h"
#import <os/log.h>
#import <UIKit/UIKit.h>

// Import the generated Swift header to access RNABCarPlayController
#if __has_include(<AudioBrowser/AudioBrowser-Swift.h>)
#import <AudioBrowser/AudioBrowser-Swift.h>
#elif __has_include("AudioBrowser-Swift.h")
#import "AudioBrowser-Swift.h"
#endif

// Protocol for optional app delegate method to start React Native in headless mode
@protocol RNABHeadlessAppDelegate <NSObject>
@optional
- (void)startReactNativeHeadless;
@end

@interface RNABCarPlaySceneDelegate ()
@property (nonatomic, strong, nullable) RNABCarPlayController *carPlayController;
@end

API_AVAILABLE(ios(14.0))
@implementation RNABCarPlaySceneDelegate

- (void)templateApplicationScene:(CPTemplateApplicationScene *)templateApplicationScene
      didConnectInterfaceController:(CPInterfaceController *)interfaceController {
    os_log_info(OS_LOG_DEFAULT, "[CarPlay] Connected");

    // Ensure React Native is started (for standalone CarPlay mode)
    // The app delegate should implement startReactNativeHeadless
    id<RNABHeadlessAppDelegate> appDelegate = (id<RNABHeadlessAppDelegate>)[UIApplication sharedApplication].delegate;
    if ([appDelegate respondsToSelector:@selector(startReactNativeHeadless)]) {
        [appDelegate startReactNativeHeadless];
    }

    // Create and start the CarPlay controller
    self.carPlayController = [[RNABCarPlayController alloc] initWithInterfaceController:interfaceController];
    [self.carPlayController start];
}

- (void)templateApplicationScene:(CPTemplateApplicationScene *)templateApplicationScene
   didDisconnectInterfaceController:(CPInterfaceController *)interfaceController {
    os_log_info(OS_LOG_DEFAULT, "[CarPlay] Disconnected");

    [self.carPlayController stop];
    self.carPlayController = nil;
}

- (void)templateApplicationScene:(CPTemplateApplicationScene *)templateApplicationScene
             didSelectNavigationAlert:(CPNavigationAlert *)navigationAlert {
    // Not used for audio apps
}

- (void)templateApplicationScene:(CPTemplateApplicationScene *)templateApplicationScene
                  didSelectManeuver:(CPManeuver *)maneuver {
    // Not used for audio apps
}

@end
