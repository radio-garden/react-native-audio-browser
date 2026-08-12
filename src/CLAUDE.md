# TypeScript API

The rules here are about the shape of the package's public surface. Both are
enforced by tests, but the tests only tell you *after* you've written it.

## Everything you export is public

`index.ts` → `AudioBrowser.ts` → `features/index.ts` is a chain of unfiltered
`export *`. There is no allowlist to add a symbol to: a new `export` in any
feature module joins the package's public surface the moment you write it.

`@internal` does **not** make a value private. `stripInternal` honours it when
building `lib/typescript` — what a consumer's TypeScript sees — but React Native
resolves `"react-native": "src/index"`, so Metro bundles the *source*, where
nothing was stripped. An `@internal` value is invisible to the type checker and
fully reachable at runtime via `require()`.

So an internal value needs a module the barrels don't re-export, imported
directly by its callers — `features/player/validateOptions.ts` is the pattern.
Types are exempt: they have no runtime representation, so `stripInternal` really
does remove them, and `@internal` is enough.

`native.ts` is the sharp case and stays out of the barrel permanently. It holds
the raw Nitro object, whose `on*` properties are single callback slots that the
emitters below already occupy — a consumer assigning one unsubscribes every hook
in the library. Feature modules import it relatively; nothing else touches it.

`public-surface.test.ts` walks the `export *` graph and fails on any reachable
value that is `@internal`, `__`-prefixed, or `nativeBrowser`.

## Events are emitters, not functions

Every `on*` export is an object you subscribe to with `addListener(cb)`, which
returns an unsubscribe function. Never export a bare `on*(cb)` subscribe
function — that split is what made the whole remote-controls guide a compile
error until it was unified.

Two emitter classes, picked by what the event *is*:

- `NativeUpdatedValue` — state with a current value (playback, options,
  progress). Subscribes to native at module load so no update is missed, and
  caches `lastValue` so hooks can read synchronously.
- `LazyNativeEmitter` — discrete events with no current value (remote controls,
  gate hits). Assigns the native callback on the first `addListener`, so an
  event nobody listens to costs nothing.

Both are created via `emitterize(cb => (nativeBrowser.onThing = cb))` and both
return the instance. Neither class is exported from the package.

`on*` observes; `handle*` overrides. `handleRemoteNext(cb)` is a plain setter
that replaces the default behaviour and takes `undefined` to clear it, while
`onRemoteNext` fires on every press regardless of who handled it.
