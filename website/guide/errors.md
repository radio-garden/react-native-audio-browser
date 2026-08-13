# Errors

Two different things can fail, and they surface separately:

- **Playback errors** — a track or stream won't play. See
  [Playback errors](#playback-errors).
- **Navigation errors** — browsing/resolving content failed (a route errored,
  the network dropped, a server returned non-2xx). See
  [Navigation errors](#navigation-errors).

For playback, the library can also **retry automatically** — transient failures
recover on their own, and while it retries the error is surfaced as _advisory_
so your UI can say what's wrong over the spinner. See
[Automatic retry](#automatic-retry).

The UI snippets import `View` / `Text` / `Button` from `react-native`; everything
else is from `react-native-audio-browser`.

## Playback errors

When the active track fails, the [playback state](/guide/playback#playback-state)
becomes `'error'` and a [`PlaybackError`](/api/features/errors/#playbackerror) is
available. Read it reactively with
[`usePlaybackError()`](/api/features/errors/#useplaybackerror) — it returns the
current error, or `undefined` once playback recovers.

`PlaybackError` is `{ kind, code, message, statusCode?, retrying? }`. **Branch
on `kind`**:

| Field        | Use it for                                                                                                                                                                       |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `kind`       | Everything user-facing. A normalized classification both platforms map onto — see the table below.                                                                               |
| `code`       | Diagnostics and telemetry **only**. Platform-specific and unstable: loader cases on iOS (`failed-to-load`, …), lower-cased ExoPlayer names on Android (`io-bad-http-status`, …). |
| `message`    | Logs. Hard-coded developer English, e.g. _"Failed to load audio track"_.                                                                                                         |
| `statusCode` | The HTTP status, when the failure came from a response.                                                                                                                          |
| `retrying`   | `true` while [automatic retry](#automatic-retry) is still working on the failure — the error is advisory, not final. See [Errors while retrying](#errors-while-retrying).        |

::: warning Don't show `message` to listeners
It is developer English and it is never localized. Map `kind` to your own copy
instead — the same error reaches the lock screen and the car, where a technical
string is especially jarring.
:::

`kind` is one of:

| `kind`           | Meaning                                                                                                         | Retrying help?   |
| ---------------- | --------------------------------------------------------------------------------------------------------------- | ---------------- |
| `'offline'`      | The device had no connection at the moment of failure. The stream may be fine.                                  | Once back online |
| `'unreachable'`  | The host could not be reached: DNS failure, refused connection, timeout, or a connection dropped while loading. | Maybe            |
| `'not-found'`    | The server said this stream is gone (HTTP 404 / 410).                                                           | No               |
| `'rejected'`     | The server refused the request (HTTP 401 / 403) — geo-blocking, an expired token.                               | No               |
| `'server-error'` | The server responded 5xx. Usually transient.                                                                    | Maybe            |
| `'unplayable'`   | Fetched, but not playable: unknown container, unsupported codec, decoder failure.                               | No               |
| `'stalled'`      | Playback had started, then stopped, and the player exhausted its recovery budget.                               | Maybe            |
| `'unknown'`      | Could not be classified. Inspect `code`.                                                                        | Maybe            |

Codes that name no cause stay `'unknown'` rather than being guessed into a
friendlier bucket — a wrong classification misleads the listener _and_ poisons
your telemetry aggregates.

Map `kind` to your own localized copy. How finely you split is a product
decision — collapsing several kinds onto one line is fine, as long as the
listener can still tell _"fix your connection"_ from _"this one is gone"_ from
_"try again in a minute"_:

```tsx
import { View, Text, Button } from 'react-native'
import { usePlaybackError, retry } from 'react-native-audio-browser'
import type { PlaybackErrorKind } from 'react-native-audio-browser'

function errorLine(kind: PlaybackErrorKind): string {
  switch (kind) {
    case 'offline':
      return t('…your "check your connection" copy')
    // Both transient — worth another try, so they can share a line.
    case 'unreachable':
    case 'server-error':
      return t('…your "could not reach it" copy')
    // Both permanent — retrying will not help.
    case 'not-found':
    case 'rejected':
      return t('…your "not available" copy')
    case 'unplayable':
      return t('…your "cannot be played" copy')
    case 'stalled':
      return t('…your "connection lost" copy')
    case 'unknown':
      return t('…your generic fallback copy')
  }
}

function PlaybackErrorView() {
  const error = usePlaybackError()
  if (!error) return null
  return (
    <View>
      <Text>{errorLine(error.kind)}</Text>
      <Button title="Try again" onPress={() => retry()} />
    </View>
  )
}
```

Leaving off the `default` case is deliberate: TypeScript then tells you when a
new `kind` is added, instead of it silently falling into a generic line.

Outside React, use `getPlaybackError()` for a snapshot or
[`onPlaybackError`](/api/features/errors/#onplaybackerror) to subscribe. The same
error is also passed to the [Now Playing formatter](/guide/now-playing#the-formatter-derived-continuous),
so you can show it on the lock screen / car too.

### Manual retry

[`retry()`](/api/features/errors/#retry) re-attempts the current item — it only
does something while the state is `'error'`. It's the action behind a "Try again"
button, and what you call after [automatic retry](#automatic-retry) has given up.
It begins a fresh load with fresh retry budgets, exactly as if the listener had
re-selected the track (a plain play command from `'error'` does the same).

## Automatic retry

Configure [`setupPlayer({ retry })`](/guide/configuration) to recover from
transient **load** failures (network blips, timeouts, a live stream dropping
mid-play) with exponential backoff. It's **off by default**, and it's native
only — the web implementation has no automatic retry, so every web error is
terminal:

```ts
import { setupPlayer } from 'react-native-audio-browser'

// Retry with the default duration budgets (12s / 2 min, see below):
await setupPlayer({ retry: true })

// Or bound it yourself:
await setupPlayer({
  retry: {
    maxRetries: 5,
    maxRetryDurationMs: 60_000,
    firstConnectMaxRetryDurationMs: 8_000
  }
})
```

Which failures qualify is the same on iOS and Android: HTTP 408, 429 and 5xx;
transport failures (DNS, timeouts, a dropped connection); and anything the
platform couldn't classify, which is assumed transient rather than fatal.
Permanent failures are never retried — 403 / 404, and media that can't be
parsed or decoded.

### Two duration budgets

Retry state is tracked per **load** — one track's playback session, created
when a track becomes current (selection, queue advance, skip) _or restarted
from a terminal error_ (`retry()`, or any play command while in `'error'`),
and surviving every automatic retry of that track. A new load starts fresh
budgets; retries within it don't.

How long the player keeps trying depends on one piece of evidence: **has this
load ever produced audio?**

| Situation                                     | Budget                                            | Why                                                                                                                                                               |
| --------------------------------------------- | ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **First connect** — the load has never played | `firstConnectMaxRetryDurationMs`, default **12s** | A stream that fails before ever playing is usually dead, and the listener is actively waiting for a verdict — seconds, not minutes.                               |
| **Recovery** — the load played, then failed   | `maxRetryDurationMs`, default **2 min**           | Playback proved the stream works. Drops are usually transient (tunnels, network handovers, a station's encoder restarting) and patience recovers them unattended. |

While the device is **offline**, the first-connect budget does not apply: the
player parks and retries the moment connectivity returns, so a stream started
in a tunnel still plays when the network comes back. Any offline observation
resets the first-connect clock entirely — after restoration the load gets
another full first-connect window, because the clock only ever measures a
contiguous online stretch. `maxRetryDurationMs` is different: it is a
wall-clock ceiling that **includes** offline time, deliberately — it exists so
playback can't surprise the listener by resuming after a long time offline.

`maxRetries` additionally caps the retry attempts of a load, counted across
both budgets and across offline/online transitions. (One Android caveat: for
segmented formats like HLS, ExoPlayer counts attempts per internal loadable —
playlist and segments separately — so an attempt cap is less predictable there;
prefer the duration budgets as the bound you rely on.)

Backoff delays grow `1s → 1.5s → 2.3s → 3.4s → 5s` (capped). Once a budget is
exhausted the state lands on `'error'`, where your `PlaybackErrorView` and
[`retry()`](#manual-retry) take over. Restarting from `'error'` — via
`retry()` or any play command — begins a **new load** of the same track:
fresh budgets, starting with the first-connect one. The tap behaves exactly
like re-selecting the track.

### Errors while retrying

While the retry loop is working, the player doesn't go silent: each failure is
classified and surfaced immediately through
[`onPlaybackChanged`](/api/features/playback/#onplaybackchanged) /
[`usePlayback()`](/guide/playback#playback-state), attached to the current
non-terminal state (`'loading'` / `'buffering'`, or `'paused'`) with
`retrying: true`. Show it as advisory — the cause over a spinner — because the
next attempt may still succeed:

```tsx
const { state, error } = usePlayback()

if (error?.retrying) {
  // Still trying: provisional copy, keep the spinner.
} else if (state === 'error') {
  // Gave up: final copy, offer retry().
}
```

`onPlaybackError` and `usePlaybackError()` stay **terminal-only**: they fire on
_both_ edges of the `'error'` state — entering it (with the error) and leaving
it (with `error: undefined`; the hook returns `undefined` again). Advisory
errors never pass through them. The same advisory error also reaches the
[Now Playing formatter](/guide/now-playing#the-formatter-derived-continuous),
so the lock screen and the car can show it too.

Advisory emissions are deduplicated by **classification** (`kind`, plus the
HTTP status when there is one) — never by the raw native `message`. The scope
is the current advisory episode: consecutive identical failures within one
retry sequence produce a single event rather than one per backoff tick, and the
dedupe resets once the advisory clears (recovery, track change, or hardening
into the terminal error).

## Navigation errors

When resolving a browse path fails, a
[`NavigationError`](/api/features/errors/#navigationerror) is produced. Read the
raw error (with `code` and HTTP details) via
[`useNavigationError()`](/api/features/errors/#usenavigationerror), or subscribe
with `onNavigationError` / snapshot with `getNavigationError()`.

`NavigationError` is `{ code, message, statusCode?, statusCodeSuccess? }`, where
`code` is one of:

| `code`                | Meaning                                                                                       |
| --------------------- | --------------------------------------------------------------------------------------------- |
| `'content-not-found'` | No route for the path, or the route returned no content (a config issue, not an HTTP 404).    |
| `'network-error'`     | The request failed (connection error, timeout, no internet).                                  |
| `'http-error'`        | Server returned non-2xx (or 2xx but parsing failed) — see `statusCode` / `statusCodeSuccess`. |
| `'callback-error'`    | Your browse callback returned an error (e.g. auth/subscription required).                     |
| `'empty-content'`     | The path resolved but the page has no tracks (empty Favorites, no search results).            |
| `'timeout'`           | Resolution didn't finish in time.                                                             |
| `'unknown-error'`     | An unexpected error (e.g. invalid configuration).                                             |

### Showing navigation errors

For display, prefer
[`useFormattedNavigationError()`](/api/features/errors/#useformattednavigationerror)
— it returns a ready-to-show [`FormattedNavigationError`](/api/features/errors/#formattednavigationerror)
(`{ title, message? }`). The **same** formatted error drives the CarPlay and
Android Auto error dialogs, so your in-app and in-car messages stay consistent:

```tsx
import { Text } from 'react-native'
import { useFormattedNavigationError } from 'react-native-audio-browser'

function BrowseError() {
  const error = useFormattedNavigationError()
  if (!error) return null
  const detail = error.message ? ` — ${error.message}` : ''
  return (
    <Text>
      {error.title}
      {detail}
    </Text>
  )
}
```

Without configuration you get sensible English defaults. To localize or override
specific cases, set `formatNavigationError` in
[`configureBrowser`](/guide/browser) — return `defaultFormatted` for the cases
you don't want to change:

```ts
import { configureBrowser } from 'react-native-audio-browser'

configureBrowser({
  formatNavigationError: ({ error, defaultFormatted, path }) => {
    if (error.code === 'http-error') {
      return {
        title: 'Server error',
        message: `Request failed (${error.statusCode})`
      }
    }
    return defaultFormatted // keep the default for everything else
  }
  // ...tabs, routes
})
```

Use the raw `useNavigationError()` when you need to branch on `code` /
`statusCode`; use `useFormattedNavigationError()` when you just need a title and
message to render.

## API summary

| API                                                               | Purpose                                                                                                                                             |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `usePlaybackError()` / `getPlaybackError()`                       | The current playback error (`{ kind, code, message, statusCode?, retrying? }`) — branch on `kind`.                                                  |
| `onPlaybackError`                                                 | Subscribe to terminal playback errors outside React (fires on entering/leaving `'error'`; advisory retrying errors arrive via `onPlaybackChanged`). |
| `retry()`                                                         | Re-attempt the current item (while state is `'error'`).                                                                                             |
| `setupPlayer({ retry })`                                          | Automatic retry with two duration budgets: first-connect (12s) and recovery (2 min). Off by default.                                                |
| `useNavigationError()` / `getNavigationError()`                   | The raw navigation error (`code`, `statusCode`, …).                                                                                                 |
| `onNavigationError`                                               | Subscribe to navigation errors outside React.                                                                                                       |
| `useFormattedNavigationError()` / `getFormattedNavigationError()` | Display-ready `{ title, message? }`, shared with CarPlay / Android Auto.                                                                            |
| `configureBrowser({ formatNavigationError })`                     | Customize / localize the formatted messages.                                                                                                        |
