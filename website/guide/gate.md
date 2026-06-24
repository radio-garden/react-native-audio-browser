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

[`setGate`](/api/features/gate/#setgate) raises the gate (or updates it in place); [`clearGate`](/api/features/gate/#cleargate) drops it and the real content returns. Set it before the car connects and it'll be there the moment it does. A gate is global and singular — calling `setGate` again replaces the current one.

A Gate is **independent** of `setupPlayer` and `configureBrowser` — call `setGate` any time after import. The usual lifecycle is: set it once at startup, then `clearGate` (or re-`setGate`) whenever the user's entitlement changes (purchase, login, logout).

A gate is just a title and an optional message. How it renders depends on the surface:

- **CarPlay** — a full-page centered message.
- **Android Auto** — one non-playable list tile: the **title** as its main line, the **message** as the subtitle (newlines collapse to spaces). It's a tile, not a full page like CarPlay, so keep both short.

There's no action button: Android Auto can't show one, and a car surface can't run a purchase flow anyway (see [Reacting to gate hits](#reacting-to-gate-hits) for the deferred-upsell pattern).

::: warning Updating a live gate resets CarPlay navigation
Calling `setGate` while a gate is already up replaces it in place — but on CarPlay it pops any drilled-in navigation back to the tab root. Harmless for a set-once gate; worth knowing if you update the message mid-session.
:::

## Gating some requests, not others

Pass a [**resolver**](/api/features/gate/#gateresolver) as the second argument. The library calls it for every browse navigation and every search, and your return value decides what happens:

- return **`false`** — let the request through
- return **`true`** — gate it with the default message (the first `setGate` argument)
- return a [**gate object**](/api/features/gate/#gate) `{ title, message? }` — gate it with a different message, just for this one

The [request](/api/features/gate/#gaterequest) tells you what's being asked: `request.kind` is `'browse'` or `'search'`. A browse request carries a `request.path`; a search request carries `request.params` (the parsed query — see the [Search](/guide/search) guide).

The common case — **gate browsing, but leave search open** — so users can still play by voice:

```ts
setGate(
  { title: 'Premium', message: 'Subscribe to browse in the car.' },
  (request) => request.kind === 'browse',
)
```

::: warning The resolver is synchronous
A resolver returns `Gate | boolean` — never a Promise — so you can't `await` an auth check inside it. Rather than check entitlement in the resolver, drive the gate from a single place: gate while locked, `clearGate` once entitled.

```ts
const gateBrowsing = () =>
  setGate(
    { title: 'Premium', message: 'Subscribe to browse in the car.' },
    (request) => request.kind === 'browse' // gate browse, allow search
  )

if (!isEntitled()) gateBrowsing() // your code, at startup

// entitlement changed (purchase / login / logout):
function onEntitlementChange(entitled: boolean) {
  entitled ? clearGate() : gateBrowsing() // refresh the car surfaces
}
```
:::

Because the resolver sees the request, you can also gate by path — block one premium branch while the rest stays open — or show a different message per request:

```ts
setGate(
  { title: 'Premium', message: 'Subscribe to unlock.' },
  (request) => {
    if (request.kind === 'search') return false          // search allowed
    if (request.path.startsWith('/premium')) return true // gate this branch
    return false                                         // everything else
  },
)
```

A search request carries `request.params` — the parsed query (`query`, `genre`, `artist`, … — see [Search](/guide/search)) — so you can gate only premium queries:

```ts
setGate(
  { title: 'Premium', message: 'Subscribe to unlock.' },
  (request) => {
    if (request.kind === 'search') {
      return request.params.genre === 'premium' // gate premium genres
    }
    return request.path.startsWith('/premium')
  },
)
```

You can also pass **only** a resolver, with no default message — return a gate object for full control, or `true` to fall back to the generic built-in chrome. That fallback is a title only (`"Unavailable"`, no body), so on CarPlay its message area is blank — pass a default gate or return a gate object if you want body copy.

```ts
setGate((request) => request.kind === 'browse')
```

If the resolver throws, the gate **fails closed** — the library gates the request rather than leak content. A deliberate `return false` is the only thing that lets a request through.

## Reacting to gate hits

You can't complete a purchase on CarPlay or Android Auto — so the pattern is to **record** that a user bumped into the gate and surface the upsell next time your phone app opens. Subscribe to [`onGate`](/api/features/gate/#ongate):

```ts
import { onGate } from 'react-native-audio-browser'

// onGate returns an unsubscribe function — keep it and call it to clean up.
const unsubscribe = onGate((event) => {
  // event.reason ('browse' | 'search') is the same axis as the resolver's
  // request.kind. The event carries only the reason, not the path/params.
  recordGateHit() // your own persisted flag
})
```

In a component, wire it through `useEffect` so it unsubscribes on unmount:

```tsx
useEffect(() => onGate((event) => recordGateHit()), [])
```

`onGate` fires **once per gated serve**, whether the gate is static or resolver-driven. There's no de-duplication, so poking around a gated car UI fires several events — debounce on your side (once per session, per day, whatever fits) if you don't want them all.

Then, on your next app launch, read the flag and show the upsell:

```ts
if (consumeGateHit()) showPremiumUpsell() // your code: read + clear flag
```
