---
layout: home

hero:
  name: Audio Browser
  text: Full-featured React Native audio
  tagline: For production apps that span app screens, lock screens, CarPlay, Android Auto, voice controls, and the web — with one shared playback and browse model.
  actions:
    - theme: brand
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: Coming from track-player?
      link: /guide/migrating-from-track-player
    - theme: alt
      text: View on GitHub
      link: https://github.com/radio-garden/react-native-audio-browser

features:
  - title: Browse-first architecture
    details: Tabs, nested lists, routes, and playable items resolved from static data, JS callbacks, or your JSON API — then rendered natively on CarPlay and Android Auto.
    link: /guide/browser
    linkText: Browser guide
  - title: Car cold start
    details: Launch directly from CarPlay or Android Auto with the phone app closed. The browse tree and playback come up on the head unit without opening the app first.
    link: /guide/automotive
    linkText: Automotive guide
  - title: Production playback
    details: Queue, background audio, playback rate, interruptions, and audio focus — plus lock-screen, notification, and headset controls.
    link: /guide/playback
    linkText: Playback guide
  - title: Real native-to-JS callbacks
    details: Route resolvers, request transforms, gates, search, and track-load interception call into your JS and use what you return — not fire-and-forget bridge events.
    link: /guide/configuration
    linkText: Configuration guide
  - title: Voice & favorites
    details: Siri and Google Assistant route into one structured search source; favorite the current track by voice and ask to “play my favorites”.
    link: /guide/search
    linkText: Search guide
  - title: Built on Nitro 🔥
    details: React Native's New Architecture, synchronous native calls, and no bridge overhead.
    link: https://nitro.margelo.com
    linkText: nitro.margelo.com
  - title: Now Playing & lock screen
    details: Metadata, artwork, timed and chapter metadata, lock-screen updates, and media-session integration across every surface.
    link: /guide/now-playing
    linkText: Now Playing guide
  - title: Live-stream ready
    details: HLS, ICY/Icecast, and progressive streams with stall recovery, reconnects, fresh URL resolution, and live-edge seeking.
    link: /guide/live-streams
    linkText: Live Streams guide
  - title: React hooks
    details: Reactive playback, queue, browse, progress, sleep-timer, and car-connection state for your own app screens.
    link: /guide/hooks
    linkText: Hooks guide
  - title: Access gates
    details: Put browse and search behind a paywall, login, or region wall with per-request resolvers — playback keeps running.
    link: /guide/gate
    linkText: Gate guide
  - title: Request & artwork control
    details: Rewrite outbound requests, add auth headers, shape URLs, and customize artwork loading per request.
    link: /guide/artwork
    linkText: Artwork guide
  - title: Platform extras
    details: AirPlay and output routing, sleep timer, Android equalizer, and battery-resume handling.
    link: /guide/audio-output
    linkText: Audio Output guide
  - title: Playback resumption
    details: Persist the last session and resume after the app is killed — including the Android system play button and “play «App»” via Siri.
    link: /guide/remote-controls
    linkText: Remote Controls guide
  - title: Web support
    details: The same AudioBrowser API works with react-native-web for playback, browse, search, and sleep timer.
    link: /guide/getting-started
    linkText: Get Started
---
