# CarPlay

## Overview

CarPlay integration for audio apps works through two systems:

1. **Browse templates** — Tab-based navigation generated from the **browse tree** you declare with `configureBrowser`, handled automatically by the library. If you haven't set that up yet, see [Basic Usage](/guide/basic-usage) for tabs, routes, and sources — this page assumes it's already configured. Every config key used below is documented in [`BrowserConfiguration`](/api/types/browser/#browserconfiguration).
2. **Siri voice search** — The search button on CarPlay launches Siri, which creates an `INPlayMediaIntent`. The library resolves it through the same [`search`](/guide/search) source that powers your in-app search, then queues and plays the results. This requires additional setup in your app (see [Siri Voice Search](#siri-voice-search) below).

On CarPlay's audio interface the search button always triggers Siri — audio apps don't use `CPSearchTemplate` (that's a navigation-app API).

The two systems are independent, but **both** require the [React Native host setup](#react-native-host-uiscene-headless) (UIScene lifecycle + headless RN boot) — that's the one prerequisite every CarPlay app needs, even browse-only. Siri then adds its own entitlement, Info.plist keys, and intent handler on top. The **Loading States** and **Album Line Navigation** sections are optional browse polish — skip them to get a minimal integration working.

::: info Before you start
This page assumes you've installed the library and have the player running: add `react-native-audio-browser`, run `pod install` in `ios/`, and call `setupPlayer()` + `configureBrowser()` at startup. See [Getting Started](/guide/getting-started) and [Basic Usage](/guide/basic-usage) first.
:::

## Setup

### Entitlement

Your app needs the CarPlay audio entitlement in your `.entitlements` file:

```xml
<key>com.apple.developer.carplay-audio</key>
<true/>
```

If your target has no `.entitlements` file yet, add one via Xcode (**Signing & Capabilities → + Capability**) and make sure the target's **`CODE_SIGN_ENTITLEMENTS`** build setting points at it — otherwise the key is ignored at build time.

The CarPlay audio entitlement also must be **granted by Apple** via the [CarPlay entitlement request](https://developer.apple.com/contact/carplay/) before it works on a device — approval can take a while, so request it early. You can still develop against the [CarPlay Simulator](#testing) meanwhile.

### Scene manifest (Info.plist)

Your scene manifest needs **two** scene configurations: the CarPlay template scene (driven by the library's `RNABCarPlaySceneDelegate`) and your phone window scene (driven by your own `PhoneSceneDelegate`, shown in the host setup below). If your app isn't scene-based yet, this whole `UIApplicationSceneManifest` is new:

```xml
<key>UIApplicationSceneManifest</key>
<dict>
    <key>UIApplicationSupportsMultipleScenes</key>
    <true/>
    <key>UISceneConfigurations</key>
    <dict>
        <key>CPTemplateApplicationSceneSessionRoleApplication</key>
        <array>
            <dict>
                <key>UISceneConfigurationName</key>
                <string>CarPlay</string>
                <key>UISceneDelegateClassName</key>
                <string>RNABCarPlaySceneDelegate</string>
            </dict>
        </array>
        <key>UIWindowSceneSessionRoleApplication</key>
        <array>
            <dict>
                <key>UISceneConfigurationName</key>
                <string>Phone</string>
                <!-- $(PRODUCT_MODULE_NAME) = your app's Swift module -->
                <key>UISceneDelegateClassName</key>
                <string>$(PRODUCT_MODULE_NAME).PhoneSceneDelegate</string>
            </dict>
        </array>
    </dict>
</dict>
```

The two `UISceneDelegateClassName` strings name the delegates for each scene: the library's `RNABCarPlaySceneDelegate` (ships with the library — you never implement it; it links in once the `AudioBrowser` pod is installed) and your own `PhoneSceneDelegate`. These manifest strings are only a fallback — once you add `configurationForConnecting` (next section), your code picks each scene's delegate. So `PhoneSceneDelegate` not resolving yet, and the CarPlay value being a bare class name, are both expected until you finish the host setup next.

## React Native host (UIScene + headless)

This section is **required for any CarPlay integration** — browse-only or Siri. CarPlay connects through scenes and can launch your app in the background with no phone window, so React Native has to be startable both on the foreground path (your phone scene) and headless (a hidden window). Set this up once; both the browse templates and Siri depend on it.

::: warning Two prerequisites
**UIScene lifecycle** — CarPlay requires a `UIWindowSceneDelegate` for the phone window. The default `npx react-native init` app isn't scene-based; if yours still sets up its window directly in `AppDelegate`, migrate to scenes first (the sections below build all the pieces).

**New Architecture host, Swift** — these snippets assume `RCTReactNativeFactory` (present on every React Native version the library supports) and a Swift `AppDelegate`. If yours is still Objective-C (`.mm`), migrating it to Swift first is simpler than translating this wiring by hand — scaffold a throwaway app (`npx @react-native-community/cli init Tmp`) and copy its `AppDelegate.swift` as a reference. ObjC works too (the bridging header is then unnecessary, see [Siri step 3](#_3-bridging-header-swift-appdelegate-only)) but isn't shown here.
:::

### The factory

These AppDelegate snippets need the New-Architecture React host imports:

```swift
import UIKit
import Intents
import React
import React_RCTAppDelegate
import ReactAppDependencyProvider
```

Create the `RCTReactNativeFactory` in `didFinishLaunchingWithOptions`, but **don't** start RN there — the phone scene starts it on the foreground path, and the headless helper starts it on the background path:

```swift
var reactNativeFactory: RCTReactNativeFactory?
private var reactNativeDelegate: ReactNativeDelegate?

func application(
  _ application: UIApplication,
  didFinishLaunchingWithOptions launchOptions:
    [UIApplication.LaunchOptionsKey: Any]? = nil
) -> Bool {
  let delegate = ReactNativeDelegate()
  delegate.dependencyProvider = RCTAppDependencyProvider()
  reactNativeDelegate = delegate
  reactNativeFactory = RCTReactNativeFactory(delegate: delegate)
  // Note: do NOT start RN here — the scene delegates own startup.
  return true
}
```

`ReactNativeDelegate` is a `RCTDefaultReactNativeFactoryDelegate` subclass **you** define (standard New-Architecture AppDelegate boilerplate — it supplies your JS bundle URL); `RCTAppDependencyProvider` is a React-core class. Neither comes from this library. The default New-Arch template generates both — if you scaffolded with it you already have them; otherwise this is the whole subclass:

```swift
class ReactNativeDelegate: RCTDefaultReactNativeFactoryDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? { bundleURL() }

  override func bundleURL() -> URL? {
#if DEBUG
    RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
#else
    Bundle.main.url(forResource: "main", withExtension: "jsbundle")
#endif
  }
}
```

### The headless helper

Two things call this helper, which is why it's required even for **browse-only** apps:

- The library's `RNABCarPlaySceneDelegate` calls `startReactNativeHeadless` on your `AppDelegate` (via an optional protocol) when CarPlay connects with no phone scene running — so a browse-only CarPlay launch boots RN automatically.
- Your Siri intent handler calls it (step 4) for a background intent launch.

::: warning Name it exactly `startReactNativeHeadless`
The library finds this method **by selector** — it must be named exactly `startReactNativeHeadless` (no args) on your `AppDelegate`, or the library's CarPlay delegate won't be able to boot RN and browse screens will hang empty.
:::

```swift
// Properties on your AppDelegate.
private var reactNativeStarted = false
private var headlessWindow: UIWindow?

/// Boots React Native into a hidden window when no UI scene exists yet.
/// Called by the library's CarPlay delegate (browse) and your Siri handler.
/// Safe to call repeatedly — it starts RN at most once.
func startReactNativeHeadless() {
  guard !reactNativeStarted else { return }
  reactNativeStarted = true

  // RCTReactNativeFactory needs a window to host the root view.
  headlessWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
  headlessWindow?.isHidden = true

  // `startReactNative(withModuleName:in:launchOptions:)` is a React-core method
  // on RCTReactNativeFactory — not a library API. "YourAppModuleName" is your
  // app.json `name` (= the AppRegistry.registerComponent string); the phone
  // scene and this headless path must use that same name.
  reactNativeFactory?.startReactNative(
    // ⚠️ replace with your app.json `name`
    withModuleName: "YourAppModuleName",
    in: headlessWindow,
    launchOptions: nil
  )
}

/// Called by the phone scene after it starts RN on the foreground path, so the
/// headless helper never double-starts it. Both paths gate on the same
/// `reactNativeStarted` flag, so RN starts exactly once.
func markReactNativeStarted() {
  reactNativeStarted = true
}
```

### The phone scene delegate

Create this as a **new `.swift` file in your app target** (e.g. `PhoneSceneDelegate.swift`, next to `AppDelegate.swift`). It must compile into your app's own module — that's why the Info.plist refers to it as `$(PRODUCT_MODULE_NAME).PhoneSceneDelegate`. It starts RN on the foreground path and flips the shared flag via `markReactNativeStarted()`:

```swift
class PhoneSceneDelegate: UIResponder, UIWindowSceneDelegate {
  var window: UIWindow?

  func scene(
    _ scene: UIScene,
    willConnectTo session: UISceneSession,
    options connectionOptions: UIScene.ConnectionOptions
  ) {
    guard session.role == .windowApplication,
          let appDelegate = UIApplication.shared.delegate as? AppDelegate,
          let windowScene = scene as? UIWindowScene else { return }

    let window = UIWindow(windowScene: windowScene)
    appDelegate.window = window
    appDelegate.markReactNativeStarted() // foreground path owns RN startup

    // Module name must exactly match the headless helper / registerComponent.
    appDelegate.reactNativeFactory?.startReactNative(
      // ⚠️ replace with your app.json `name`
      withModuleName: "YourAppModuleName",
      in: window,
      launchOptions: nil
    )
    self.window = window
  }
}
```

See the example app's [`AppDelegate.swift`](https://github.com/radio-garden/react-native-audio-browser/blob/main/apps/example-native/ios/AudioBrowserExample/AppDelegate.swift) and [`PhoneSceneDelegate.swift`](https://github.com/radio-garden/react-native-audio-browser/blob/main/apps/example-native/ios/AudioBrowserExample/PhoneSceneDelegate.swift) for the complete, copy-pasteable versions.

### Routing scene connections

Now that both delegates exist, route each incoming scene to the right one from your `AppDelegate` — the library's `RNABCarPlaySceneDelegate` for CarPlay, your `PhoneSceneDelegate` for the phone window:

```swift
func application(
  _ application: UIApplication,
  configurationForConnecting connectingSceneSession: UISceneSession,
  options: UIScene.ConnectionOptions
) -> UISceneConfiguration {
  if connectingSceneSession.role == UISceneSession.Role.carTemplateApplication {
    let config = UISceneConfiguration(
      name: "CarPlay", sessionRole: connectingSceneSession.role)
    // RNABCarPlaySceneDelegate is an ObjC class the library ships, so it's
    // resolved by name rather than imported.
    config.delegateClass =
      NSClassFromString("RNABCarPlaySceneDelegate") as? UIResponder.Type
    return config
  }
  // Phone scene — your own delegate, defined above.
  let config = UISceneConfiguration(
    name: "Phone", sessionRole: connectingSceneSession.role)
  config.delegateClass = PhoneSceneDelegate.self
  return config
}
```

As noted in the scene manifest above, the `delegateClass` you set here is what actually routes each scene; the Info.plist names are just the fallback. Seeing each delegate in both places is expected.

One coupling that **is** load-bearing: the `name:` you pass to `UISceneConfiguration` (`"CarPlay"` / `"Phone"`) must match a `UISceneConfigurationName` in the manifest. Rename one without the other and iOS crashes at connect time with _"no UISceneConfiguration named …"_.

### The assembled `AppDelegate`

The fragments above all live on one `AppDelegate`. Here is the whole class in one piece — the authoritative copy-paste. It **replaces** the `@main class AppDelegate` your RN template generated in `ios/<app>/AppDelegate.swift` (you keep one `@main`; don't add a second). The Siri `handlerFor` method at the end is needed only if you add [Siri Voice Search](#siri-voice-search):

```swift
import UIKit
import Intents
import React
import React_RCTAppDelegate
import ReactAppDependencyProvider

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  var reactNativeFactory: RCTReactNativeFactory?
  private var reactNativeDelegate: ReactNativeDelegate?
  private var reactNativeStarted = false
  private var headlessWindow: UIWindow?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions:
      [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    let delegate = ReactNativeDelegate()
    delegate.dependencyProvider = RCTAppDependencyProvider()
    reactNativeDelegate = delegate
    reactNativeFactory = RCTReactNativeFactory(delegate: delegate)
    return true // don't start RN here — the scene delegates own startup
  }

  // Route each connecting scene to its delegate.
  func application(
    _ application: UIApplication,
    configurationForConnecting connectingSceneSession: UISceneSession,
    options: UIScene.ConnectionOptions
  ) -> UISceneConfiguration {
    if connectingSceneSession.role
      == UISceneSession.Role.carTemplateApplication {
      let config = UISceneConfiguration(
        name: "CarPlay", sessionRole: connectingSceneSession.role)
      config.delegateClass =
        NSClassFromString("RNABCarPlaySceneDelegate") as? UIResponder.Type
      return config
    }
    let config = UISceneConfiguration(
      name: "Phone", sessionRole: connectingSceneSession.role)
    config.delegateClass = PhoneSceneDelegate.self
    return config
  }

  // Boots RN into a hidden window when there's no UI scene (background launch).
  func startReactNativeHeadless() {
    guard !reactNativeStarted else { return }
    reactNativeStarted = true
    headlessWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
    headlessWindow?.isHidden = true
    reactNativeFactory?.startReactNative(
      // ⚠️ replace with your app.json `name`
      withModuleName: "YourAppModuleName",
      in: headlessWindow,
      launchOptions: nil
    )
  }

  // Called by PhoneSceneDelegate so the headless path won't double-start RN.
  func markReactNativeStarted() { reactNativeStarted = true }

  // Siri only — needed when you add Siri Voice Search (step 4). Requires the
  // bridging header so `RNABAudioBrowser` is visible in Swift.
  func application(
    _ application: UIApplication,
    handlerFor intent: INIntent
  ) -> Any? {
    startReactNativeHeadless()
    return RNABAudioBrowser.handler(for: intent)
  }
}

// Same file as AppDelegate. Standard New-Arch host delegate —
// supplies the bundle URL.
class ReactNativeDelegate: RCTDefaultReactNativeFactoryDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? { bundleURL() }

  override func bundleURL() -> URL? {
#if DEBUG
    RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
#else
    Bundle.main.url(forResource: "main", withExtension: "jsbundle")
#endif
  }
}
```

This is the complete `AppDelegate.swift` (the `@main class AppDelegate` plus its `ReactNativeDelegate` in the same file). `PhoneSceneDelegate` lives in its **own** `PhoneSceneDelegate.swift` (shown above). Those two files are the complete native side.

::: warning Don't blindly overwrite an AppDelegate you've customized
This block is the minimal shape. If your existing `AppDelegate` / `ReactNativeDelegate` already has extra overrides — bundle customization, linking setup, push/deep-link handlers, `customize(rootView:)`, extra modules — **keep them**. Merge the CarPlay additions (the factory split, `configurationForConnecting`, the headless helper, `markReactNativeStarted`, and the Siri `handlerFor`) into your file rather than replacing it wholesale.
:::

### Where to call `configureBrowser`

A headless launch boots RN into a hidden window with **no components mounted**, so a `configureBrowser` call inside a component's `useEffect` will never run on a CarPlay/Siri cold launch. Call it (and `setupPlayer`) as a **top-level side-effect in `index.js`** — alongside `AppRegistry.registerComponent` — so it executes when the JS bundle loads, mounted UI or not.

First, because `configureBrowser` **replaces** the whole config, build one object with everything — tabs, routes, and (for Siri) search — together:

```ts
const browserConfig = {
  // Tabs along the top of the CarPlay browser (max 4 — capped for
  // Android Auto + CarPlay compatibility; extra tabs dropped with a warning).
  tabs: [{ title: 'Home', path: '/' }],

  // routes: path → BrowserSource (static page, callback, or HTTP).
  routes: {
    '/': async () => ({
      title: 'Home',
      children: [{ title: 'Crunchy Greens FM', src: 'https://...' }]
    })
  },

  // search: required for Siri voice search (omit for browse-only).
  search: { baseUrl: 'https://api.example.com/search' }
}
```

Then apply it as a top-level side-effect in `index.js`:

```js
// index.js
import { AppRegistry } from 'react-native'
import { configureBrowser, setupPlayer } from 'react-native-audio-browser'
import App from './App'
import { name as appName } from './app.json'
import { browserConfig } from './browserConfig' // the object above

AppRegistry.registerComponent(appName, () => App)

// Runs on every launch — including headless CarPlay / Siri — because it's a
// module side-effect, not tied to a mounted component.
void (async () => {
  await setupPlayer()
  configureBrowser(browserConfig)
})()
```

The `"YourAppModuleName"` in the Swift snippets above is exactly this `appName` — your `app.json` `name`, identical to the string passed to `AppRegistry.registerComponent` (the same in the default RN template). The headless helper and the phone scene must both start RN with that name.

## Siri Voice Search

Siri voice search builds on the [React Native host setup](#react-native-host-uiscene-headless) above — make sure that's in place first. Then complete the steps below, in order:

- **Step 0 — configure a `search` source** (the warning box just below; required, easy to forget)
- **Steps 1–4** — Siri entitlement, Info.plist keys, bridging header, intent handler

::: warning Step 0 (required) — a configured `search` source
Siri voice search resolves spoken queries through the **same [`search`](/guide/search) source as your in-app search**. If you haven't set one, the mic will open, hear "play jazz", and return **nothing** — steps 1–4 will look wired up but produce silence. Configure it once:

```ts
configureBrowser({
  // …your tabs / routes …
  search: {
    // GET https://api.example.com/search?q=jazz — the library appends
    // `q` plus structured voice params (`mode`, `genre`, `artist`,
    // `album`, `reference`) and expects a page object back:
    // { title?, children: Track[] }, where each playable child Track
    // needs at least `title` + `src`.
    baseUrl: 'https://api.example.com/search'
  }
})
```

`configureBrowser` **replaces** the entire configuration each time it's called — it doesn't merge. So this isn't a second call: fold `search` into the single `configureBrowser({ tabs, routes, search, … })` you make at startup (see [Where to call `configureBrowser`](#where-to-call-configurebrowser)). The snippets on this page show one key at a time for brevity.

Naming note: in the **callback** form the spoken text arrives as `query` (`search: async ({ query }) => …`); on the **HTTP** form the library sends it as the wire param **`q`** (`?q=jazz`). Same value, two names depending on which form you use.

See [Search](/guide/search) for the full `SearchParams` (genre / artist / album / `mode` / `reference`), the callback form (return [`Track`](/api/types/browser-nodes/#track)`[]` yourself), and `transform` for rewriting requests. This is required for Siri; the browse templates work without it.
:::

CarPlay's tab bar already has a **search button that launches Siri automatically** — you get that for free once the steps below are wired up. Separately, the library can also show an **"Ask Siri to Play Audio" cell inside a list** as an extra entry point. This `carPlaySiriListButton` is optional polish — it's not what enables Siri search. To add it, set [`carPlaySiriListButton`](/api/types/browser/#carplaysirilistbuttonposition) on the resolved content returned by your route source:

```ts
configureBrowser({
  // `routes` maps a path → a `BrowserSource`: a static page object, an async
  // callback returning one (as here), or an HTTP request config. See
  // Basic Usage and the BrowserSource type: /api/types/browser/#browsersource
  routes: {
    '/': async () => ({
      title: 'Home',
      carPlaySiriListButton: 'top',
      children: [{ title: 'Crunchy Greens FM', src: 'https://...' }]
    })
  }
})
```

(Tabs themselves come from your separate [`tabs`](/api/types/browser/#tabs) config, not from this route — the `'/'` route here is just where the button is attached. See [Basic Usage](/guide/basic-usage) for how `tabs` and `routes` fit together.)

The `carPlaySiriListButton` property accepts `'top'` or `'bottom'` to control where the system assistant cell appears. It only affects CarPlay — it has no effect on the phone UI.

::: tip
The button just launches Siri — it does **nothing** until the entitlement, Info.plist keys, and intent handler in the steps below are in place. Wire those up first, or tapping it will appear to do nothing.
:::

To complete the setup, your app needs the Siri entitlement, a couple of Info.plist keys, a bridging header, and a one-line handler in your AppDelegate. Media intents are handled in-app: Siri delivers the `INPlayMediaIntent` straight to your `AppDelegate`.

::: info iOS version
The library's minimum is **iOS 16**. The other version numbers on this page are finer-grained capability notes within that floor: the in-app handler API (`application(_:handlerFor:)`) exists from iOS 14 so you never need to gate on it, and the automatic CarPlay loading spinner is iOS 18.4+.
:::

### 1. Siri entitlement

Add the Siri entitlement to the **same `.entitlements` file** as the CarPlay key — both live in one `<dict>`:

```xml
<!-- YourApp.entitlements -->
<plist version="1.0">
<dict>
    <key>com.apple.developer.carplay-audio</key>
    <true/>
    <key>com.apple.developer.siri</key>
    <true/>
</dict>
</plist>
```

The reliable way to add this is Xcode → **Signing & Capabilities → + Capability → Siri**, which writes the key and enables **SiriKit on your App ID** (via automatic signing or the developer portal). Unlike the CarPlay audio entitlement, Siri does **not** require a manual Apple approval request — enabling the capability is enough.

### 2. Siri Info.plist keys

Add Siri usage description and supported media categories to your app's Info.plist:

```xml
<key>NSSiriUsageDescription</key>
<string>Siri is used for voice-controlled media playback via CarPlay.</string>
<key>INIntentsSupported</key>
<array>
    <string>INPlayMediaIntent</string>
    <!-- Optional — only for "like/dislike" / "add to favorites": -->
    <string>INUpdateMediaAffinityIntent</string>
    <string>INAddMediaIntent</string>
</array>
<key>INSupportedMediaCategories</key>
<array>
    <!-- PLACEHOLDER — replace with the categories matching YOUR content.
         E.g. INMediaCategoryRadio for live radio, INMediaCategoryPodcasts
         for podcasts, INMediaCategoryMusic for music. List every one you
         support. -->
    <string>INMediaCategoryRadio</string>
</array>
```

`INIntentsSupported` registers the intents your app can handle. The library's handler vends a handler for three intents:

- **`INPlayMediaIntent`** — search / resume / play (the core voice-search flow).
- **`INUpdateMediaAffinityIntent`** — "like" / "dislike" the current track.
- **`INAddMediaIntent`** — add the current track to favorites.

Register only the ones you want; `INPlayMediaIntent` alone is enough for voice search. The [`INSupportedMediaCategories`](https://developer.apple.com/documentation/sirikit/inmediacategory) values tell Siri which spoken requests to route to your app — if they don't cover your content, Siri may hand the request to a **different app**, so list every category you actually support.

### 3. Bridging Header (Swift AppDelegate only)

The intent API lives in the library's CocoaPod, **`AudioBrowser`** (the umbrella header for the `react-native-audio-browser` package). An **Objective-C** AppDelegate just `#import <AudioBrowser/RNABAudioBrowser.h>` directly and skips this step.

A **Swift** AppDelegate reaches it through a bridging header. If your app already has one (check `SWIFT_OBJC_BRIDGING_HEADER`), add the import to it; otherwise create one and point that build setting at it:

**YourApp-Bridging-Header.h:**

```objc
#import <AudioBrowser/RNABAudioBrowser.h>
```

::: tip If the import won't resolve
`<AudioBrowser/...>` resolves only once the `AudioBrowser` pod is linked. Run `pod install` (from `ios/`) after adding the npm dependency, and confirm the pod is named **`AudioBrowser`** in your `Podfile.lock`. A "module 'AudioBrowser' not found" error almost always means `pod install` hasn't run.
:::

### 4. AppDelegate intent handler

For in-app intent handling, implement `application(_:handlerFor:)` on your `AppDelegate`, boot RN headless (the [`startReactNativeHeadless()`](#the-headless-helper) helper from the host setup), and return the library's handler object. This is the same method already shown (commented "Siri only") in the [assembled `AppDelegate`](#the-assembled-appdelegate) — add it once, not twice; it's repeated here in isolation:

```swift
import Intents

func application(
  _ application: UIApplication,
  handlerFor intent: INIntent
) -> Any? {
  // The intent may have cold-launched us with no scene — boot RN first;
  // the library's handler waits for RN before resolving.
  startReactNativeHeadless()
  return RNABAudioBrowser.handler(for: intent)
}
```

`RNABAudioBrowser.handler(for:)` is the library's **only** intent entry point. It returns an opaque handler object for intents it supports (`INPlayMediaIntent`, `INUpdateMediaAffinityIntent`, `INAddMediaIntent`) and `nil` for anything else — there is no separate registration or setup call. The library resolves the spoken query through your configured [`search`](/guide/search) source (the same one your in-app search uses — it is **not** a browse route), queues the results, and starts playback automatically.

::: tip Cold-launch ordering is handled for you
You don't need to race `configureBrowser` against the intent. The handler **waits for your configured `search` source (and any [Gate](/guide/gate) — a paywall/region check that can intercept playback — if you use one) to be ready** before resolving — so even on a cold launch with RN not yet booted, the query resolves only after your JS has run `configureBrowser`. Just call it as a top-level side-effect (see [Where to call `configureBrowser`](#where-to-call-configurebrowser)).
:::

> **Why a handler object, not `application(_:handle:completionHandler:)`?** The latter is not a real `UIApplicationDelegate` method, so iOS never calls it. With no in-app handler registered, the system falls back to a `UISIntentForwardingAction`, which crashes in `-[INHandleIntentForwardingActionResponse isSuccess]`. Returning a handler from `application(_:handlerFor:)` keeps iOS on the in-app path. **No separate Intents Extension is required** — everything runs in-app.

### How It Works

1. User taps the search button on a CarPlay tab
2. Siri activates and listens for a voice query
3. iOS creates an `INPlayMediaIntent` carrying the parsed query
4. The system asks your app for a handler via `application(_:handlerFor:)` and drives the intent against it
5. The library resolves the request and starts playback — **resuming** the last session when the user just said your app's name, otherwise **searching** your content (genre/artist/album/etc. are forwarded as structured search params)
6. CarPlay's Now Playing screen updates automatically

## Loading States

::: tip Optional browse polish
This and Album Line Navigation below are optional refinements to the browse templates — skip them for a minimal integration.
:::

Browse screens show a loading state while their content resolves — pushed destinations, tabs loading lazily, and the startup screen while tabs are queried. On iOS 18.4+ the system loading spinner is shown automatically. Older iOS versions have no spinner API; those screens stay blank unless you supply a [`carPlayLoadingTitle`](/api/types/browser/#carplayloadingtitle):

```ts
configureBrowser({
  carPlayLoadingTitle: t('loading')
  // ...
})
```

The title renders as the list's centered empty state and disappears as soon as content arrives.

## Album Line Navigation

The album line on the Now Playing screen can navigate into the browse tree. Two things are needed for it to become tappable: the track must have an **`album`** string (CarPlay builds the tappable line from it — see the warning below), and an **`albumPath`** pointing at a browse path (a `path` in your [`routes`](/guide/basic-usage), so the destination must exist there). Set `albumPath` to make the album line tappable while that track is active — tapping pushes that path:

```ts
{
  title: 'Sweet Pea',
  artist: 'The Radishes',
  album: 'Greens',
  src: 'https://…',
  albumPath: '/album/greens'
}
```

For tracks without an `albumPath`, configure a [`resolveAlbumPath`](/api/types/browser/#resolvealbumpath) resolver. It runs when the active track changes (not at tap time), so the album line only becomes tappable when there is actually somewhere to go:

```ts
configureBrowser({
  // `slugify` here is your own helper (illustrative) — turn an album name
  // into a browse path that exists in your `routes`.
  resolveAlbumPath: (track) =>
    track.album ? `/album/${slugify(track.album)}` : undefined
  // ...
})
```

Return a browse path to enable the album line for that track, or `undefined` to leave it untappable.

::: warning Album metadata required
CarPlay renders the tappable album/artist button as a **separate line built from the track's `album`** (visible as the third metadata line, with a chevron). Tracks without an `album` have no string for CarPlay to turn into a button — the artist line alone is never tappable. Set `album` on any track that should offer album-line navigation. See [Now Playing](/guide/now-playing) for the full field-by-surface rendering matrix.
:::

## Testing

Test your app in the `CarPlay Simulator.app`, which is part of the [Additional Tools for Xcode](https://developer.apple.com/download/all/?q=additional%20tools%20for%20xcode). Siri voice search works there, so you can exercise the full intent flow without a car. CarPlay support on the iOS Simulator can be a little flaky — if something looks broken, retry or fall back to a physical device for final verification.

### Troubleshooting

| Symptom                                        | Likely cause                                                                                          |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Siri mic opens but returns nothing             | No [`search` source](#siri-voice-search) configured (Step 0).                                         |
| The "Ask Siri" cell does nothing               | Siri entitlement / Info.plist keys / intent handler not wired (steps 1–4).                            |
| CarPlay browse screens hang empty              | `startReactNativeHeadless` missing or misnamed — the library calls it by exact selector.              |
| Build error: _module 'AudioBrowser' not found_ | `pod install` hasn't linked the `AudioBrowser` pod.                                                   |
| Crash: _no UISceneConfiguration named …_       | The `name:` in `configurationForConnecting` doesn't match `UISceneConfigurationName` in the manifest. |

## Reference

See the [example app](https://github.com/radio-garden/react-native-audio-browser/tree/main/apps/example-native/ios/AudioBrowserExample) for a complete working implementation.
