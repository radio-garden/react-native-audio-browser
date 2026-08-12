# Networking in native callbacks (don't use `fetch`)

**If a browse route resolver, search source, or request `transform` does its own
networking with React Native's built-in `fetch` — and it works in your app but
_hangs_ when the device is asleep (browsing on CarPlay or Android Auto with the
screen off, or answering a voice intent) — you are in the right place.** The
browse screen goes blank because the request never resolves. The fix is to fetch
with
[`react-native-nitro-fetch`](https://github.com/margelo/react-native-nitro-fetch),
or to let the library do the request natively.

## The problem

The library invokes the async callbacks you provide **from native code**,
whenever the system asks for content — including while your app is asleep:

- browse **route resolvers** (`configureBrowser({ routes })`),
- the **search source** (`configureBrowser({ search })`),
- request **`transform`** callbacks.

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

When the device is asleep, React Native's timer module is paused — on iOS,
`RCTTiming` pauses the `CADisplayLink` that drives JS timers — so `setTimeout`
callbacks don't fire. Native networking (`RCTNetworking`) **does** perform the
request and receive the response, but the `setTimeout(0)` that would hand it back
to your `await` never runs. The promise sits unresolved until the screen wakes,
and your browse list spins or shows blank. Android suspends JS timers the same
way while the app is backgrounded behind Android Auto, so the symptom and the fix
are identical there.

This isn't specific to this library — it affects any `fetch` on a sleeping
device, and upgrading doesn't help: current React Native still ships
`whatwg-fetch`.

## The fix: `react-native-nitro-fetch`

[`react-native-nitro-fetch`](https://github.com/margelo/react-native-nitro-fetch)
is a drop-in `fetch` replacement that runs the request on a native thread
(`URLSession` on iOS, Cronet on Android) and resolves through **Nitro's
microtask queue** — no `setTimeout`, no `whatwg-fetch`, no `RCTTiming`
dependency. So it completes even when the callback runs while the device is
asleep. Since this library is already a Nitro module, it's a natural fit.

```bash
npm install react-native-nitro-fetch react-native-nitro-modules
```

`react-native-nitro-modules` needs React Native 0.75+, and you'll need a native
rebuild (`pod install` + rebuild) after installing.

Then add one import at the top of any module with a native-invoked callback — it
shadows the global `fetch`, so the call sites don't change:

```ts
import { configureBrowser } from 'react-native-audio-browser'
import { fetch } from 'react-native-nitro-fetch'

configureBrowser({
  tabs: [{ title: 'Browse', url: '/browse' }],
  routes: {
    '/browse': async ({ path }) => {
      const res = await fetch('https://api.example.com/browse')
      const { children } = await res.json()
      return { url: path, title: 'Browse', children }
    }
  }
})
```

Use the same import in your search source and request `transform`s.

## Even simpler: let the library fetch natively

If a route just maps a path to an HTTP request, you don't have to write a JS
callback at all. Give the browse source a `baseUrl` and the **library performs
the request natively** for each navigated path — no JS networking, nothing to
stall:

```ts
configureBrowser({
  // Each browse path is fetched over HTTP on the native side.
  browse: { baseUrl: 'https://api.example.com' },
  tabs: [{ title: 'Browse', url: '/browse' }]
})
```

The navigated path is appended to `baseUrl`; to remap or shape the request, use a
[`transform`](/guide/browser) (still native). The search source accepts the same
request-config form, so it can be served natively too. Reach for
`react-native-nitro-fetch` only when you need logic a request config can't
express (combining sources, reading local storage, custom shaping).

## When it's safe to use plain `fetch`

This only affects code the library invokes from native while the app is asleep.
Networking driven by **your own app UI in the foreground** — a screen the user is
looking at — runs on the normal JS loop and is unaffected; plain `fetch` is fine
there. Rule of thumb: if the OS can run the code with the screen off, don't
depend on `fetch`.

## Further reading

- [`react-native-nitro-fetch`](https://github.com/margelo/react-native-nitro-fetch)
- [JS timers don't fire when the app is backgrounded (RN #38711)](https://github.com/facebook/react-native/issues/38711)
- [Timers & fetch freeze behind a native activity (RN #54534)](https://github.com/facebook/react-native/issues/54534)
