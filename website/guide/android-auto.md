# Android Auto

## Overview

Android Auto shows your [browse tree](/guide/basic-usage) on the car screen and plays through the same player as your app. Two systems drive it:

1. **Browse tree** — Android Auto reads your content from a `MediaBrowserService`, which mirrors your browser routes. The library ships this service.
2. **Voice search** — "Hey Google, play…" arrives as a `MEDIA_PLAY_FROM_SEARCH` intent, which the library routes to your `search` source.

Unlike CarPlay — which needs entitlements and scene delegates — Android Auto needs almost no app-side code. The library's own `AndroidManifest.xml` declares the media-browser service, the media session, the voice-search intent filter, and the foreground-service permissions, and those **merge into your app automatically**. You only have to declare your app as an automotive media app.

## Setup

### 1. Declare the automotive app descriptor

Add this `<meta-data>` inside the `<application>` tag of your app's `AndroidManifest.xml`:

```xml
<meta-data
    android:name="com.google.android.gms.car.application"
    android:resource="@xml/automotive_app_desc" />
```

### 2. Add the descriptor resource

Create `android/app/src/main/res/xml/automotive_app_desc.xml`:

```xml
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

That's the whole setup. The `MediaBrowserService`, media session, `MEDIA_PLAY_FROM_SEARCH` intent filter, headless service for background playback, and `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions all come from the library's merged manifest — you don't declare them yourself.

## Voice search

Google Assistant requests ("play some jazz", "play the radishes") arrive as a `MEDIA_PLAY_FROM_SEARCH` intent. The library normalizes it into the same structured `SearchParams` your in-app and Siri searches use and calls your `search` source — no extra manifest setup, since the library declares the intent filter.

Android sends a subset of what iOS does: the query plus music focuses (genre / artist / album), always with `reference: 'unknown'`. See [Search](/guide/search) for how to resolve these requests and the cross-platform differences.

## Browse display

Two per-Track fields control how Android Auto renders a browsable's children:

```ts
{
  path: '/browse/stations',
  title: 'Stations',
  // render this list's children as grid tiles ('list' is the default)
  childrenStyle: 'grid',
}
```

- **`childrenStyle`** — `'list'` (rows) or `'grid'` (tiles), set on the **parent** to choose how its children appear.
- **`groupTitle`** — set on children to add section headers. Contiguous children sharing a `groupTitle` render under one header.

```ts
children: [
  { title: 'Morning Show', src: '…', groupTitle: 'Live now' },
  { title: 'Afternoon Drive', src: '…', groupTitle: 'Live now' },
  { title: 'Late Night Jazz', src: '…', groupTitle: 'Up next' }
]
```

## Now Playing buttons

The buttons on the Now Playing screen come from the same
[`remoteButtonLayout`](/guide/remote-controls#button-layout-android) that drives the
notification — there's no car-specific option. Two behaviors are particular to
the car, though:

- **The head unit has the last word.** Slots are a preference. A unit with a
  spare position may promote the first `overflow` entry onto the main row, so
  a button you placed in overflow can still appear beside play/pause.
- **Fewer buttons, cleaner row.** If you want just
  `jump-back │ play/pause │ jump-forward`, leave the rest out of the layout
  entirely — they keep working from the steering wheel and headset regardless.

## Testing

Test in a real car, or in Google's **Desktop Head Unit (DHU)** emulator:

1. Install the **Android Auto Desktop Head Unit emulator** from the SDK Manager (_SDK Tools → Android Auto Desktop Head Unit emulator_).
2. In the phone's **Android Auto** settings, enable Developer Mode (tap the version 10×), then **"Start head unit server"**.
3. Connect the phone over USB and run the DHU.

See Google's [Test Android Auto apps with DHU](https://developer.android.com/training/cars/testing/dhu) for the full walkthrough. Your app appears under the media apps — browse the tree, exercise voice search, and verify artwork, grid/list styles, and section headers.

## Reference

See the [example app](https://github.com/radio-garden/react-native-audio-browser/tree/main/apps/example-native/android) for a complete working manifest and descriptor.
