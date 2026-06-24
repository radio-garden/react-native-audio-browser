# Gate

When part of your catalog is premium, login-only, or region-locked, you need a way to block it on the car surfaces — without building a separate UI for them. That's what a **Gate** is: you hand the library a short message, and it shows that in place of the browse content on **CarPlay** and **Android Auto**, and can turn away voice searches.

One rule worth keeping in mind: **a Gate blocks *finding*, never *hearing*.** Whatever is already playing keeps going, "resume" and "play this" still work by voice, and the queue and now-playing are left alone.

Two things it never touches:

- **Your own in-app browse UI.** The Gate only covers content the *library* serves; the screens you draw in your app are yours to gate with your own paywall.
- **Playback.** It lives on the find/resolve path, so audio is never interrupted.

::: warning Voice search isn't only a "car" thing
A Siri request ("Hey Siri, play jazz on App") can come from the phone or lock screen, not just CarPlay — and it still goes through the Gate. That's why the common setup below **gates browsing but lets search through**: voice-play keeps working everywhere, while the car's browse tabs stay walled off.
:::

## Setting a gate

```ts
import { setGate, clearGate } from 'react-native-audio-browser'

setGate({ title: 'Premium', message: 'Subscribe to browse in the car.' })
// …later, when the user is entitled:
clearGate()
```

`setGate` raises the gate (or updates it in place); `clearGate` drops it and the real content returns. Set it before the car connects and it'll be there the moment it does. A gate is global and singular — calling `setGate` again replaces the current one.

A gate is just a title and an optional message. How it renders depends on the surface:

- **CarPlay** — a full-page centered message.
- **Android Auto** — one non-playable list tile: the **title** as its main line, the **message** as the subtitle (newlines collapse to spaces). It's a tile, not a full page like CarPlay, so keep both short.

There's no action button: Android Auto can't show one, and a car surface can't run a purchase flow anyway (see [Reacting to gate hits](#reacting-to-gate-hits) for the deferred-upsell pattern).

::: warning Updating a live gate resets CarPlay navigation
Calling `setGate` while a gate is already up replaces it in place — but on CarPlay it pops any drilled-in navigation back to the tab root. Harmless for a set-once gate; worth knowing if you update the message mid-session.
:::

## Gating some requests, not others

Pass a **resolver** as the second argument. The library calls it for every browse navigation and every search, and your return value decides what happens:

- return **`false`** — let the request through
- return **`true`** — gate it with the default message (the first `setGate` argument)
- return a **gate object** `{ title, message? }` — gate it with a different message, just for this one

The request tells you what's being asked: `request.kind` is `'browse'` or `'search'`. A browse request carries a `request.path`; a search request carries `request.params` (the parsed query — see the [Search](/guide/search) guide).

The common case — **gate browsing, but leave search open** — so users can still play by voice:

```ts
setGate(
  { title: 'Premium', message: 'Subscribe to browse in the car.' },
  (request) => request.kind === 'browse',
)
```

Because the resolver sees the request, you can also gate by path — block one premium branch while the rest stays open — or show a different message per request:

```ts
setGate(
  { title: 'Premium', message: 'Subscribe to unlock.' },
  (request) => {
    if (request.kind === 'search') return false                 // search always allowed
    if (request.path.startsWith('/premium')) return true        // gate this branch, default message
    return false                                                // everything else open
  },
)
```

You can also pass **only** a resolver, with no default message — return a gate object for full control, or `true` to fall back to a generic built-in message ("Unavailable"):

```ts
setGate((request) => request.kind === 'browse')
```

If the resolver throws, the gate **fails closed** — the library gates the request rather than leak content. A deliberate `return false` is the only thing that lets a request through.

## Reacting to gate hits

You can't complete a purchase on CarPlay or Android Auto — so the pattern is to **record** that a user bumped into the gate and surface the upsell next time your phone app opens. Subscribe to `onGate`:

```ts
import { onGate } from 'react-native-audio-browser'

const unsubscribe = onGate((event) => {
  // event.reason is 'browse' or 'search'
  markGateHit(event.reason)        // your own flag; show an upsell on next app open
})
```

`onGate` fires **once per gated serve**, whether the gate is static or resolver-driven. There's no de-duplication, so poking around a gated car UI fires several events — debounce on your side (once per session, per day, whatever fits) if you don't want them all.
