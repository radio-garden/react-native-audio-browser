#import <Foundation/Foundation.h>
#import <Intents/Intents.h>

NS_ASSUME_NONNULL_BEGIN

@interface RNABAudioBrowser : NSObject

/// Return this from your AppDelegate's `application:handlerForIntent:` (iOS 14+
/// in-app handling). Vends the library's handler for supported intents —
/// `INPlayMediaIntent` (search / resume / play), `INUpdateMediaAffinityIntent`
/// (like / dislike the current track) and `INAddMediaIntent` (add the current
/// track to favorites) — or nil for unsupported intents. Register the ones you
/// want in `INIntentsSupported`.
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
