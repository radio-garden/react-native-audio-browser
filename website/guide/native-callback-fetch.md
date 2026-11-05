---
description: "Why React Native's fetch hangs inside the callbacks you pass to configureBrowser, such as browse route callbacks and search sources, when the device is asleep, and what to use instead."
---

# Networking in native callbacks (don't use `fetch`)

**Does a callback you pass to `configureBrowser` network with React Native's
built-in `fetch`, and work in your app but _hang_ when the device is asleep?
Browsing on CarPlay or Android Auto with the screen off, or answering a voice
intent, are typical cases. Then you are in the right place.**

The browse screen goes blank because the request never resolves. The fix is to
fetch with
[`react-native-nitro-fetch`](https://github.com/margelo/react-native-nitro-fetch),
or to let the library do the request natively.

## The problem

The library invokes the async callbacks you provide **from native code**,
whenever the system asks for content — including while your app is asleep:

- browse **route callbacks** (`configureBrowser({ routes })`),
- the **search source** (`configureBrowser({ search })`),
- request **`transform`** callbacks,
- `media` and `artwork` **`resolve`** callbacks,
- **config resolvers**, such as `request: async () => …`,
- a **`tabs`** callback.

A locked iPhone browsing your library on CarPlay, or a "play jazz" voice command
with the screen dark, runs these callbacks with the app backgrounded. The first
native call into JS runs fine — but the moment your callback `await`s a `fetch`,
it hangs until the phone wakes.

### Why `fetch` hangs while asleep

React Native's `fetch` is the [`whatwg-fetch`](https://github.com/JakeChampion/fetch)
polyfill, and it resolves its promise with a **zero-delay timer**, not a
microtask:

```js
// inside whatwg-fetch
setTimeout(function () {
  resolve(new Response(body, options))
}, 0)
```

While the app is in the background, JS timers don't fire reliably
([RN #38711](https://github.com/react/react-native/issues/38711)). Native
networking (`RCTNetworking`) **does** receive the response, but the
`setTimeout(0)` that hands it to your `await` doesn't run. The promise hangs
until the screen wakes, and the browse list spins or shows blank.

On Android, React Native's timer manager stops firing JS timers once the host
activity pauses, unless a headless JS task is running. An app backgrounded
behind Android Auto therefore shows the same symptom, and the same fix applies.

This isn't specific to this library — it affects any `fetch` on a sleeping
device, and upgrading doesn't help: current React Native still ships
`whatwg-fetch`.

## The fix: `react-native-nitro-fetch`

[`react-native-nitro-fetch`](https://github.com/margelo/react-native-nitro-fetch)
is a drop-in `fetch` replacement that runs the request on a native thread
(`URLSession` on iOS, Cronet on Android) and resolves through **Nitro's
microtask queue** — no `setTimeout`, no `whatwg-fetch`, no dependency on JS
timers.

It therefore completes even when the callback runs while the device is asleep.
This library is already a Nitro module, so it's a natural fit.

```bash
npm install react-native-nitro-fetch react-native-nitro-modules
```

You already have `react-native-nitro-modules` (it's a peer dependency of this
library); you'll need a native rebuild (`pod install` + rebuild) after
installing.

Then add one import at the top of any module with a native-invoked callback — it
shadows the global `fetch`, so the call sites don't change:

```ts
import { configureBrowser } from 'react-native-audio-browser'
import { fetch } from 'react-native-nitro-fetch'

configureBrowser({
  tabs: [{ title: 'Browse', path: '/browse' }],
  routes: {
    '/browse': async ({ path }) => {
      const res = await fetch('https://api.example.com/browse')
      const { children } = await res.json()
      return { path: path, title: 'Browse', children }
    }
  }
})
```

Do the same in every module that holds a callback from the list above, not only
routes.

## Even simpler: let the library fetch natively

If a route just maps a path to an HTTP request, you don't have to write a JS
callback at all. Give the browse source a `baseUrl` and the **library performs
the request natively** for each navigated path — no JS networking, nothing to
stall:

```ts
configureBrowser({
  // Each browse path is fetched over HTTP on the native side.
  browse: { baseUrl: 'https://api.example.com' },
  tabs: [{ title: 'Browse', path: '/browse' }]
})
```

The navigated path is appended to `baseUrl`. To remap or shape the request, use
a [`transform`](/guide/browser#shaping-requests). The request itself stays
native, so a transform that does no networking of its own is safe.

The search source accepts the same request-config form, so it can be served
natively too. Reach for `react-native-nitro-fetch` only when you need logic a
request config can't express (combining sources, reading local storage, custom
shaping).

## When it's safe to use plain `fetch`

This only affects code the library invokes from native while the app is asleep.
Networking driven by **your own app UI in the foreground** — a screen the user is
looking at — runs on the normal JS loop and is unaffected; plain `fetch` is fine
there.

Rule of thumb: if the OS can run the code with the screen off, don't
depend on `fetch`.

## Further reading

- [`react-native-nitro-fetch`](https://github.com/margelo/react-native-nitro-fetch)
- [JS timers don't fire when the app is backgrounded (RN #38711)](https://github.com/react/react-native/issues/38711)
- [Timers & fetch freeze behind a native activity (RN #54534)](https://github.com/react/react-native/issues/54534)
