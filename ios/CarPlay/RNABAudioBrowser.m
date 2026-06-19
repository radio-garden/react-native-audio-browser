#import "RNABAudioBrowser.h"

@implementation RNABAudioBrowser

+ (nullable id)handlerForIntent:(INIntent *)intent {
    // The handlers are internal Swift classes exposed to the ObjC runtime via
    // @objc(...); each conforms to the matching INIntentHandling protocol.
    NSString *handlerClassName = nil;
    if ([intent isKindOfClass:[INPlayMediaIntent class]]) {
        handlerClassName = @"RNABMediaIntentHandler";
    } else if ([intent isKindOfClass:[INUpdateMediaAffinityIntent class]]) {
        handlerClassName = @"RNABMediaAffinityHandler";
    }
    if (!handlerClassName) {
        return nil;
    }
    Class cls = NSClassFromString(handlerClassName);
    return cls ? [[cls alloc] init] : nil;
}

@end
