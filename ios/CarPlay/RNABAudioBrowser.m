#import "RNABAudioBrowser.h"

@implementation RNABAudioBrowser

+ (nullable id)handlerForIntent:(INIntent *)intent {
    // RNABMediaIntentHandler is an internal Swift class exposed to the ObjC
    // runtime via @objc(...); it conforms to INPlayMediaIntentHandling.
    if ([intent isKindOfClass:[INPlayMediaIntent class]]) {
        Class cls = NSClassFromString(@"RNABMediaIntentHandler");
        if (cls) {
            return [[cls alloc] init];
        }
    }
    return nil;
}

@end
