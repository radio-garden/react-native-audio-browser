# CarPlay

## Overview

CarPlay integration for audio apps works through two systems:

1. **Browse templates** — Tab-based navigation that mirrors your app's browser routes, handled automatically by the library.
2. **Siri voice search** — The search button on CarPlay launches Siri, which creates an `INPlayMediaIntent`. This requires additional setup in your app (see [Siri Voice Search](#siri-voice-search) below).

CarPlay audio apps do **not** use `CPSearchTemplate` (that's for navigation apps). The search button on CarPlay's audio interface always triggers Siri.

## Setup

### 1. Entitlements

Your app needs the CarPlay audio entitlement in your `.entitlements` file:

```xml
<key>com.apple.developer.carplay-audio</key>
<true/>
```

This must be requested from Apple via the [CarPlay entitlement request](https://developer.apple.com/contact/carplay/).

### 2. Info.plist

Ensure your scene manifest includes the CarPlay template application scene:

```xml
<key>CPTemplateApplicationSceneSessionRoleApplication</key>
<array>
    <dict>
        <key>UISceneConfigurationName</key>
        <string>CarPlay</string>
        <key>UISceneDelegateClassName</key>
        <string>RNABCarPlaySceneDelegate</string>
    </dict>
</array>
```

### 3. Scene Configuration

In your `AppDelegate`, return the library's CarPlay scene delegate for CarPlay connections:

```swift
func application(
  _ application: UIApplication,
  configurationForConnecting connectingSceneSession: UISceneSession,
  options: UIScene.ConnectionOptions
) -> UISceneConfiguration {
  if connectingSceneSession.role == UISceneSession.Role.carTemplateApplication {
    let config = UISceneConfiguration(name: "CarPlay", sessionRole: connectingSceneSession.role)
    config.delegateClass = NSClassFromString("RNABCarPlaySceneDelegate") as? UIResponder.Type
    return config
  }
  // ... phone scene config
}
```

## Siri Voice Search

The library can show an "Ask Siri to Play Audio" button on CarPlay list templates. To enable it, set `carPlaySiriListButton` on the resolved content returned by your route source:

```ts
configureBrowser({
  routes: [{
    path: '/',
    source: async () => ({
      title: 'Home',
      carPlaySiriListButton: 'top',
      children: [
        { title: 'Song 1', src: 'https://...' },
      ]
    })
  }]
})
```

The `carPlaySiriListButton` property accepts `'top'` or `'bottom'` to control where the system assistant cell appears. It only affects CarPlay — it has no effect on the phone UI.

To complete the setup, your app also needs an Intents Extension, a bridging header, and a one-line handler in your AppDelegate.

### 1. Entitlements

Add the Siri entitlement to your `.entitlements` file:

```xml
<key>com.apple.developer.siri</key>
<true/>
```

### 2. Info.plist

Add Siri usage description and supported media categories to your app's Info.plist:

```xml
<key>NSSiriUsageDescription</key>
<string>Siri is used for voice-controlled media playback via CarPlay.</string>
<key>INSupportedMediaCategories</key>
<array>
    <string>INMediaCategoryMusic</string>
</array>
```

### 3. Intents Extension

The Siri search button requires an **Intents Extension** target. The extension receives the Siri intent, resolves media items, and forwards it to the main app for playback.

Add an Intents Extension target to your Xcode project (File > New > Target > Intents Extension). The extension's bundle identifier must be a child of the main app's (e.g. `com.myapp.SiriIntentExtension`), and it must be embedded in the app via the "Embed App Extensions" build phase.

The extension needs two files:

**IntentHandler.swift** — resolves media items and forwards to the main app:

```swift
import Intents

class IntentHandler: INExtension, INPlayMediaIntentHandling {

  func resolveMediaItems(for intent: INPlayMediaIntent, with completion: @escaping ([INPlayMediaMediaItemResolutionResult]) -> Void) {
    // Create a pass-through media item carrying the search term.
    // Actual search/playback is handled by the main app after .handleInApp.
    let searchTerm = intent.mediaSearch?.mediaName ?? ""
    let mediaItem = INMediaItem(
      identifier: searchTerm,
      title: searchTerm,
      type: .song,
      artwork: nil
    )
    completion([.success(with: mediaItem)])
  }

  func handle(intent: INPlayMediaIntent, completion: @escaping (INPlayMediaIntentResponse) -> Void) {
    completion(INPlayMediaIntentResponse(code: .handleInApp, userActivity: nil))
  }
}
```

**Info.plist** — declares `INPlayMediaIntent` support:

```xml
<key>NSExtension</key>
<dict>
    <key>NSExtensionAttributes</key>
    <dict>
        <key>IntentsRestrictedWhileLocked</key>
        <array/>
        <key>IntentsRestrictedWhileProtectedDataUnavailable</key>
        <array/>
        <key>IntentsSupported</key>
        <array>
            <string>INPlayMediaIntent</string>
        </array>
        <key>SupportedMediaCategories</key>
        <array>
            <string>INMediaCategoryMusic</string>
        </array>
    </dict>
    <key>NSExtensionPointIdentifier</key>
    <string>com.apple.intents-service</string>
    <key>NSExtensionPrincipalClass</key>
    <string>$(PRODUCT_MODULE_NAME).IntentHandler</string>
</dict>
```

### 4. Bridging Header

Create a bridging header to access the library's intent handling API from Swift:

**YourApp-Bridging-Header.h:**

```objc
#import <AudioBrowser/RNABAudioBrowser.h>
```

Set `SWIFT_OBJC_BRIDGING_HEADER` in your target's build settings to point to this file.

### 5. AppDelegate

When the extension forwards the intent via `.handleInApp`, the system calls `application(_:handle:completionHandler:)` on your `AppDelegate`. Delegate to the library:

```swift
import Intents

func application(
  _ application: UIApplication,
  handle intent: INIntent,
  completionHandler: @escaping (INIntentResponse) -> Void
) {
  RNABAudioBrowser.handleMediaIntent(intent, completionHandler: completionHandler)
}
```

The library searches using your configured browser search route, queues the results, and starts playback automatically.

### How It Works

1. User taps the search button on a CarPlay tab
2. Siri activates and listens for a voice query
3. iOS creates an `INPlayMediaIntent` with the transcribed search term
4. The Intents Extension resolves a pass-through media item and responds with `.handleInApp`
5. The system forwards the intent to the main app via `application(_:handle:completionHandler:)`
6. The library searches your content and starts playback
7. CarPlay's Now Playing screen updates automatically

## Headless Mode

CarPlay can launch your app in the background (without the phone UI). The library supports this via headless React Native startup. See the example app's `AppDelegate.startReactNativeHeadless()` for the implementation pattern.

## Testing

Siri cannot be tested in the iOS Simulator or CarPlay Simulator. You must test on a physical device connected to CarPlay (wireless CarPlay is recommended so you can use Xcode debugging simultaneously).

## Reference

See the [example app](https://github.com/anthropics/react-native-audio-browser/tree/main/apps/example-native/ios/AudioBrowserExample) for a complete working implementation including the Intents Extension.
