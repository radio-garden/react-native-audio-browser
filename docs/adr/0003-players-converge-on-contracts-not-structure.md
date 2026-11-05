# Platform players converge on contracts, not on class structure

**Status:** accepted

The three player implementations (iOS in Swift, Android in Kotlin, web in TypeScript) converge on **behaviour and contracts**. Each is free to use its **platform-idiomatic structure**.

The same pure decision logic exists on every platform, under the same names where a platform has its own unit:

- the playback state machine (`nextPlaybackState` on iOS and web, `PlaybackStateMachine.transitions` on Android)
- the playing-state derivation (`PlayingStateFactory` on web and Android, the static `Player.playingState(playWhenReady:state:)` on iOS)
- queue navigation and shuffle ordering (`QueueManager` on web and iOS; on Android, media3's playlist with the `shuffleOrderLedBy` shuffle order)
- the remote-command rule: prefer the consumer handler, else the default transport action, then always emit the event

How those pieces are wired together is allowed to differ per platform.

The native platforms compose: a thin entry object owns a player, which owns focused managers. That reflects real boundaries:

- **Android** runs playback in a separate `MediaLibraryService` (a foreground service with its own lifecycle, outliving the activity).
- **iOS** coordinates AVPlayer through a `Player` that owns a `PlayerEngine` (ADR 0012), with CarPlay scene delegates reaching in via weak references.

Web has none of those boundaries: it is one object graph in one JavaScript context. So it uses class inheritance (`Player` → `QueuePlayer` → `NativeAudioBrowser`, the unified browser/player entry) where the native platforms use composition.

## Considered options

- **Mirror native composition on web**: flatten the inheritance chain so the entry class owns a player instead of extending one. Rejected: on web this buys no behaviour, only ~30 forwarding methods plus converting protected-override hooks (the state setter that emits events, the load override) into callbacks.

  It does not meaningfully shrink the entry class either, since its size is browser + manager wiring, not inherited player methods. Composition on native is justified by lifecycle boundaries that don't exist in a single JS context, so copying the structure is cargo-culting.

- **Share implementation across platforms** — not possible: Swift, Kotlin, and TypeScript cannot share code. Convergence can only ever live at the design/contract level, which is exactly what this ADR commits to.
- **Converge on contracts, diverge on structure (chosen)** — extract the bug-prone, behaviour-defining logic into pure, independently-tested units that mirror the native ones, by name where the native side has a matching unit, and let each platform keep its idiomatic wiring.

## Consequences

- **A cross-platform maintainer maps behaviour, not class diagrams.** The shared pure units, named alike where both sides have one, are the mental bridge; the differing top-level arrangement (inheritance on web, composition on native) is expected and fine.
- **Behavioural parity is enforced by porting test suites, not by matching structure.** When native changes a transition table or a derivation, the web port (and its ported tests) is updated to match; the class layout is irrelevant to that.
- **"Make web look like native" is not, by itself, a reason to refactor.** A structural change to web must justify itself on web's own terms (testability, clarity, a real boundary) — not on symmetry with another platform.
