#import <Foundation/Foundation.h>
#import <CarPlay/CarPlay.h>

NS_ASSUME_NONNULL_BEGIN

/// CarPlay scene delegate for Audio Browser.
///
/// This is implemented in Objective-C to avoid C++ interop issues
/// with the generated Swift-to-Obj-C header when using Nitro modules.
API_AVAILABLE(ios(14.0))
@interface RNABCarPlaySceneDelegate : UIResponder <CPTemplateApplicationSceneDelegate>

@end

NS_ASSUME_NONNULL_END
