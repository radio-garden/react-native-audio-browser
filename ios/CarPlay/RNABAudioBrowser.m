#import "RNABAudioBrowser.h"

#if __has_include(<AudioBrowser/AudioBrowser-Swift.h>)
#import <AudioBrowser/AudioBrowser-Swift.h>
#elif __has_include("AudioBrowser-Swift.h")
#import "AudioBrowser-Swift.h"
#endif

@implementation RNABAudioBrowser

+ (nullable id)handlerForIntent:(INIntent *)intent {
    return [RNABIntentHandlers handlerForIntent:intent];
}

@end
