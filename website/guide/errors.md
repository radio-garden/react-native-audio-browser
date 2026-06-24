# Errors

Two different things can fail, and they surface separately:

- **Playback errors** — a track or stream won't play. See
  [Playback errors](#playback-errors).
- **Navigation errors** — browsing/resolving content failed (a route errored,
  the network dropped, a server returned non-2xx). See
  [Navigation errors](#navigation-errors).

For playback, the library can also **retry automatically** — often the error
never reaches the user. See [Automatic retry](#automatic-retry).

The UI snippets import `View` / `Text` / `Button` from `react-native`; everything
else is from `react-native-audio-browser`.

## Playback errors

When the active track fails, the [playback state](/guide/playback#playback-state)
becomes `'error'` and a [`PlaybackError`](/api/features/errors/#playbackerror)
(`{ code, message }`) is available. Read it reactively with
[`usePlaybackError()`](/api/features/errors/#useplaybackerror) — it returns the
current error, or `undefined` once playback recovers. (`code` is an opaque
string identifier; show `message` to users.)

```tsx
import { View, Text, Button } from 'react-native'
import { usePlaybackError, retry } from 'react-native-audio-browser'

function PlaybackErrorView() {
  const error = usePlaybackError()
  if (!error) return null
  return (
    <View>
      <Text>Couldn't play this: {error.message}</Text>
      <Button title="Try again" onPress={() => retry()} />
    </View>
  )
}
```

Outside React, use `getPlaybackError()` for a snapshot or
[`onPlaybackError`](/api/features/errors/#onplaybackerror) to subscribe. The same
error is also passed to the [Now Playing formatter](/guide/now-playing#the-formatter-derived-continuous),
so you can show it on the lock screen / car too.

### Manual retry

[`retry()`](/api/features/errors/#retry) re-attempts the current item — it only
does something while the state is `'error'`. It's the action behind a "Try again"
button, and what you call after [automatic retry](#automatic-retry) has given up.

## Automatic retry

Configure [`setupPlayer({ retry })`](/guide/configuration) to recover from
transient **load** failures (network blips, timeouts) without bothering the user
— a track that fails *after* it's already playing goes straight to `'error'` for
manual [`retry()`](#manual-retry) instead. It's **off by default**:

```ts
import { setupPlayer } from 'react-native-audio-browser'

// Retry indefinitely with exponential backoff (2-minute cap):
await setupPlayer({ retry: true })

// Or bound it:
await setupPlayer({ retry: { maxRetries: 5, maxRetryDurationMs: 60_000 } })
```

| `retry` value | Behavior |
| --- | --- |
| `false` / omitted | No automatic retry (default). |
| `true` | Retry indefinitely, capped at `maxRetryDurationMs` (default **2 min**). |
| `{ maxRetries }` | Retry up to N times. |
| `{ maxRetries, maxRetryDurationMs }` | Retry up to N times or until the time cap. |

Backoff delays grow `1s → 1.5s → 2.3s → 3.4s → 5s` (capped). The duration cap
exists so playback doesn't surprise the listener by resuming after a long time
offline. Once retries are exhausted the state lands on `'error'`, where your
`PlaybackErrorView` and `retry()` take over.

## Navigation errors

When resolving a browse path fails, a
[`NavigationError`](/api/features/errors/#navigationerror) is produced. Read the
raw error (with `code` and HTTP details) via
[`useNavigationError()`](/api/features/errors/#usenavigationerror), or subscribe
with `onNavigationError` / snapshot with `getNavigationError()`.

`NavigationError` is `{ code, message, statusCode?, statusCodeSuccess? }`, where
`code` is one of:

| `code` | Meaning |
| --- | --- |
| `'content-not-found'` | No route for the path, or the route returned no content (a config issue, not an HTTP 404). |
| `'network-error'` | The request failed (connection error, timeout, no internet). |
| `'http-error'` | Server returned non-2xx (or 2xx but parsing failed) — see `statusCode` / `statusCodeSuccess`. |
| `'callback-error'` | Your browse callback returned an error (e.g. auth/subscription required). |
| `'empty-content'` | The path resolved but the container has no children (empty Favorites, no search results). |
| `'timeout'` | Resolution didn't finish in time. |
| `'unknown-error'` | An unexpected error (e.g. invalid configuration). |

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
  return <Text>{error.title}{detail}</Text>
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

| API | Purpose |
| --- | --- |
| `usePlaybackError()` / `getPlaybackError()` | The current playback error (`{ code, message }`). |
| `onPlaybackError` | Subscribe to playback errors outside React. |
| `retry()` | Re-attempt the current item (while state is `'error'`). |
| `setupPlayer({ retry })` | Automatic retry of transient load failures (off by default). |
| `useNavigationError()` / `getNavigationError()` | The raw navigation error (`code`, `statusCode`, …). |
| `onNavigationError` | Subscribe to navigation errors outside React. |
| `useFormattedNavigationError()` / `getFormattedNavigationError()` | Display-ready `{ title, message? }`, shared with CarPlay / Android Auto. |
| `configureBrowser({ formatNavigationError })` | Customize / localize the formatted messages. |
