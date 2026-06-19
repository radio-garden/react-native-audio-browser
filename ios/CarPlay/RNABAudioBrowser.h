#import <Foundation/Foundation.h>
#import <Intents/Intents.h>

NS_ASSUME_NONNULL_BEGIN

@interface RNABAudioBrowser : NSObject

/// Return this from your AppDelegate's `application:handlerForIntent:` (iOS 14+
/// in-app handling). For `INPlayMediaIntent` it vends a handler that searches,
/// queues, and plays; nil for unsupported intents.
///
/// @code
/// func application(_ application: UIApplication, handlerFor intent: INIntent) -> Any? {
///     RNABAudioBrowser.handler(for: intent)
/// }
/// @endcode
///
/// A handler must be returned — without one iOS falls back to
/// `UISIntentForwardingAction` and crashes in
/// `-[INHandleIntentForwardingActionResponse isSuccess]`.
+ (nullable id)handlerForIntent:(INIntent *)intent;

@end

NS_ASSUME_NONNULL_END
