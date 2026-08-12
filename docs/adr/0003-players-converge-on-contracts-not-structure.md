# Platform players converge on contracts, not on class structure

**Status:** accepted

The three player implementations — iOS (Swift), Android (Kotlin), and web (TypeScript) — converge on **behaviour and contracts**, and are each free to use their **platform-idiomatic structure**. Concretely: the same pure decision logic exists on every platform under the same names — the playback state machine (`nextPlaybackState` / `PlaybackStateMachine`), the playing-state derivation (`PlayingStateFactory` / `PlayingStateManager`), queue navigation and shuffle ordering (`QueueManager`), and the remote-command "prefer the consumer handler, else the default transport action, then always emit the event" rule. How those pieces are wired together is allowed to differ per platform.

The native platforms compose: a thin entry object owns a player, which owns focused managers. That isn't an aesthetic choice — it reflects real boundaries. Android runs playback in a separate `MediaLibraryService` (a background process); iOS coordinates AVPlayer through a coordinator with CarPlay scene delegates reaching in via weak references. Web has none of those boundaries — it is one object graph in one JavaScript context — so it uses class inheritance (`Player` → `PlaylistPlayer` → the unified browser/player entry) where the native platforms use composition.

## Considered options

- **Mirror native composition on web** — flatten the inheritance chain so the entry class owns a player instead of extending one. Rejected: on web this buys no behaviour, only ~30 forwarding methods plus converting protected-override hooks (the state setter that emits events, the load override) into callbacks. It does not meaningfully shrink the entry class either, since its size is browser + manager wiring, not inherited player methods. Composition on native is justified by process/lifecycle boundaries that simply don't exist in a single JS context, so copying the structure is cargo-culting.
- **Share implementation across platforms** — not possible: Swift, Kotlin, and TypeScript cannot share code. Convergence can only ever live at the design/contract level, which is exactly what this ADR commits to.
- **Converge on contracts, diverge on structure (chosen)** — extract the bug-prone, behaviour-defining logic into pure, independently-tested units that mirror the native ones by name, and let each platform keep its idiomatic wiring.

## Consequences

- **A cross-platform maintainer maps behaviour, not class diagrams.** The shared, identically-named pure units are the mental bridge; the differing top-level arrangement (inheritance on web, composition on native) is expected and fine.
- **Behavioural parity is enforced by porting test suites, not by matching structure.** When native changes a transition table or a derivation, the web port (and its ported tests) is updated to match; the class layout is irrelevant to that.
- **"Make web look like native" is not, by itself, a reason to refactor.** A structural change to web must justify itself on web's own terms (testability, clarity, a real boundary) — not on symmetry with another platform.
