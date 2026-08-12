# Voice media intents are handled in-app through a single play funnel (no Intents extension)

**Status:** accepted

Siri media intents (`INPlayMediaIntent`, and later `INAddMediaIntent` / `INUpdateMediaAffinityIntent`) and consumer-authored App Intents are handled in the **main app process** — via `application(_:handlerForIntent:)` and `AppIntent.perform()` — never in an Intents app extension. Both entry points funnel through one native path: resolve → queue → play → surface Now Playing, guarded by the existing player-readiness gate. The consumer supplies the intent→tracks mapping through a `resolvePlayMedia` config callback (cross-platform) and authors their own App Shortcuts against native `AudioBrowserCommand` primitives; the library never ships pre-baked shortcuts.

## Considered options

- **An Intents app extension** (the shape of Apple's `ManagingAudioWithSiriKit` sample: resolve in the extension, return `.handleInApp` to the app). Rejected: an extension is a separate, memory-limited, short-lived process that cannot share the React Native runtime or live player state, so its resolve step would need a native reimplementation or a second RN runtime booted per invocation. iOS 14+ in-app handling reuses the already-configured runtime for free. (RN _can_ run in extensions, but it's memory-gated — per React Native's docs, unreliable near the 16 MB Today-widget limit, only "more viable" at the ~120 MB share-extension limit.)
- **Inlining intent handling in the consumer app's native code.** Rejected: the player, queue, search, readiness gate and CarPlay Now-Playing surfacing are library-owned and not exposed to the app; the app would have to expose or reimplement them — strictly more work than owning the handling in the library, where the intent entry point already lives.
- **Library-shipped App Shortcuts.** Rejected: App Shortcut phrases and entities are compile-time and app-specific (the app name, the consumer's vocabulary). The library exposes command primitives instead, and the consumer writes the ~15-line `AppIntent` / `AppShortcutsProvider`.

## Consequences

- **No resolve-phase disambiguation.** Without an extension there is no `INMediaItem` resolution for Siri to speak or disambiguate; the funnel plays the consumer-resolved tracks and returns `.success`. Acceptable for "play the result" audio UX.
- **One shared timeout budget.** The readiness gate plus `resolvePlayMedia` must complete inside the assistant's ~10s window. The funnel enforces a single hard budget (not additive waits) and fails natively rather than letting the assistant hang.
- **Non-playback intents need a background-task assertion.** `onAddMedia` / `onUpdateAffinity` don't start an audio session, so a backgrounded app may be suspended before the JS callback's network write completes; their native handling takes a `beginBackgroundTask` assertion.
- **Cold-start resume needs native persistence.** A no-criteria "resume" intent on a cold launch has nothing to resume. iOS gains a `PlaybackStateStore` mirroring the Android contract (persist track / position / repeat / shuffle / speed; restore on the resume path) so the last session is restorable without JS, reusing the shared `/__recent` convention.
- **`AppIntent.perform()` calls the player directly**, not a deep link — avoiding the React Native "runtime not ready" lost-deep-link race that deep-link-based App Intents hit.
