# Queue

The **queue** is the ordered list of tracks the player works through. The track
at the current position is the **active track** — it's what plays, and what
next/previous move between. This guide covers building the queue, editing it,
moving between tracks, and reading it back for an "up next" UI.

For the first-time setup and the browse-tree concept, see
[Basic Usage](/guide/basic-usage#the-queue). For transport (play / pause / seek
*within* the active track), see [Playback](/guide/playback). The system
surfaces (lock screen, car) are in [Now Playing](/guide/now-playing).

The UI snippets below import `View` / `Text` / `Button` / `FlatList` from
`react-native`; those import lines are omitted for brevity. Everything else
comes from `react-native-audio-browser`.

## Setting the queue

[`setQueue`](/api/features/queue/#setqueue) replaces the entire queue.
Optionally pass which track to make active and where to start it:

```ts
import { setQueue, play } from 'react-native-audio-browser'

setQueue(tracks) // replace; first track becomes active
setQueue(tracks, 2) // start on index 2
setQueue(tracks, 2, 30) // ...30 seconds in
```

One thing to know:

- **It doesn't start playback.** `setQueue` leaves the play/pause state as-is —
  if the player was paused it stays paused. Call
  [`play()`](/api/features/playback/#play) to begin (see [Playback](/guide/playback)).

So the usual "load these and play" is two calls:

```ts
setQueue(tracks) // or setQueue(tracks, startIndex)
play() // start playback
```

## Reading the queue

[`useQueue`](/api/features/queue/#usequeue) returns the current
[`Track[]`](/api/types/browser-nodes/#track) and re-renders whenever it changes
(add, remove, reorder, or metadata update):

```tsx
import { useQueue } from 'react-native-audio-browser'

function QueueList() {
  const queue = useQueue()
  return (
    <FlatList
      data={queue}
      keyExtractor={(track, i) => track.id ?? String(i)}
      renderItem={({ item }) => <Text>{item.title}</Text>}
    />
  )
}
```

Outside React, read a snapshot with
[`getQueue()`](/api/features/queue/#getqueue), or a single entry with
[`getTrack(index)`](/api/features/queue/#gettrack) (returns `undefined` if the
index is empty). To react to changes without a hook, subscribe to
[`onQueueChanged`](/api/features/queue/#onqueuechanged) — it fires with the new
queue on every add, remove, reorder, or metadata update.

## The active track

[`useActiveTrack`](/api/features/queue/#useactivetrack) returns the currently
playing [`Track`](/api/types/browser-nodes/#track) (or `undefined` when nothing
is loaded):

```tsx
import { useActiveTrack } from 'react-native-audio-browser'

function NowPlaying() {
  const track = useActiveTrack()
  return <Text>{track?.title ?? 'Nothing playing'}</Text>
}
```

Outside React, use [`getActiveTrack()`](/api/features/queue/#getactivetrack) and
[`getActiveTrackIndex()`](/api/features/queue/#getactivetrackindex). To react to
*changes* — e.g. for per-track analytics — subscribe to
[`onActiveTrackChanged`](/api/features/queue/#onactivetrackchanged), which also
reports the track you're leaving:

```ts
import { onActiveTrackChanged } from 'react-native-audio-browser'

const unsubscribe = onActiveTrackChanged.addListener((event) => {
  console.log('now playing', event.track?.title)
  console.log('left off', event.lastTrack?.title, 'at', event.lastPosition)
})
```

## Moving between tracks

Jump to a track by index, or step to the next/previous one. Each optionally
takes a start position **in seconds**:

```ts
import {
  skip,
  skipToNext,
  skipToPrevious
} from 'react-native-audio-browser'

skip(3) // jump to index 3
skipToNext() // next track
skipToPrevious() // previous track
skipToNext(10) // next track, starting 10 seconds in
```

This is how you move *between* tracks
([`skip`](/api/features/queue/#skip),
[`skipToNext`](/api/features/queue/#skiptonext),
[`skipToPrevious`](/api/features/queue/#skiptoprevious)); seeking *within* the
active track (`seekTo` / `seekBy`) lives in [Playback](/guide/playback).

## Editing the queue

[`add`](/api/features/queue/#add), [`move`](/api/features/queue/#move),
[`remove`](/api/features/queue/#remove),
[`removeUpcomingTracks`](/api/features/queue/#removeupcomingtracks), and
[`load`](/api/features/queue/#load) edit the queue without rebuilding it.
Indexes refer to positions in the current queue (the same order `useQueue`
returns).

```ts
import {
  add,
  move,
  remove,
  removeUpcomingTracks,
  load
} from 'react-native-audio-browser'

add(track) // append one track (also accepts a Track[])
add(track, 0) // insert before index 0 (front of the queue)

move(4, 1) // move index 4 to index 1
// toIndex past the end moves the track to the end.

remove(2) // remove index 2 (also accepts number[])
removeUpcomingTracks() // drop everything after the active track

load(track) // replace the active track (or start the queue if empty)
```

The second argument to `add` is an **absolute** index, not "after the active
track". For a real **play-next** button, insert right after the active track:

```ts
import { add, getActiveTrackIndex } from 'react-native-audio-browser'

const i = getActiveTrackIndex()
add(track, i === undefined ? 0 : i + 1)
```

**Removing the active track** doesn't stop playback: the next track becomes
active, or — if you removed the last one — the first track does.

## Repeat and shuffle

[Repeat mode](/api/features/queue/#userepeatmode) is one of `'off'`, `'track'`,
or `'queue'`:

| Mode | Behavior |
| --- | --- |
| `'off'` | Stop when the last track finishes (the default). |
| `'track'` | Repeat the active track forever. |
| `'queue'` | Loop the whole queue. |

```tsx
import {
  setRepeatMode,
  useRepeatMode,
  toggleShuffle,
  useShuffle
} from 'react-native-audio-browser'

function RepeatButton() {
  const mode = useRepeatMode()
  const next = mode === 'off' ? 'queue' : mode === 'queue' ? 'track' : 'off'
  return (
    <Button
      title={`Repeat: ${mode}`}
      onPress={() => setRepeatMode(next)}
    />
  )
}

function ShuffleButton() {
  const on = useShuffle()
  return (
    <Button
      title={on ? 'Shuffle: on' : 'Shuffle: off'}
      onPress={() => toggleShuffle()}
    />
  )
}
```

[`setShuffle(true | false)`](/api/features/queue/#setshuffle) sets it directly;
[`toggleShuffle()`](/api/features/queue/#toggleshuffle) flips it.

## When the queue ends

With repeat `'off'`, playback pauses when the last track finishes and the
playback state becomes `'ended'` (see [Playback](/guide/playback#playback-state)).
To react — autoplay more, log a session, show a prompt — subscribe to
[`onQueueEnded`](/api/features/queue/#onqueueended):

```ts
import { onQueueEnded } from 'react-native-audio-browser'

const unsubscribe = onQueueEnded.addListener(({ track, position }) => {
  // `track` is the index that was active; `position` is where it stopped.
  loadMoreAndPlay()
})
```

## A complete "Up Next" screen

`useQueue` plus `useActiveTrack` is enough for a reorderable list with
tap-to-play and remove:

```tsx
import { View, Text, Button, FlatList } from 'react-native'
import {
  useQueue,
  useActiveTrack,
  getActiveTrackIndex,
  skip,
  move,
  remove
} from 'react-native-audio-browser'

function UpNext() {
  const queue = useQueue()
  // `useActiveTrack` re-renders the list when the active track changes; we
  // compare each row by index (more robust than by id, which is optional on a
  // Track). `getActiveTrackIndex()` is fresh on each render.
  const active = useActiveTrack()
  const activeIndex = active ? getActiveTrackIndex() : undefined

  return (
    <FlatList
      data={queue}
      keyExtractor={(track, i) => track.id ?? String(i)}
      renderItem={({ item, index }) => (
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <Text
            onPress={() => skip(index)}
            style={index === activeIndex ? { fontWeight: 'bold' } : null}
          >
            {item.title}
          </Text>
          {index > 0 && (
            <Button title="↑" onPress={() => move(index, index - 1)} />
          )}
          <Button title="✕" onPress={() => remove(index)} />
        </View>
      )}
    />
  )
}
```

A `<Button>` can't be a child of `<Text>`, so each row is a `<View>` with the
title and buttons as siblings. The `keyExtractor` falls back to the array index
when a track has no `id` — fine for a static list, but for a **reorderable**
list like this one, give your tracks a stable `id` so `move`/`remove` don't
confuse row identity.

## A note on units

All position arguments are **seconds** — `seekTo`, `seekBy`, the
`initialPosition` on `skip` / `skipToNext` / `skipToPrevious`, and `setQueue`'s
`startPosition`.

## API summary

| API | Purpose |
| --- | --- |
| `setQueue(tracks, startIndex?, startPosition?)` | Replace the queue (doesn't auto-play; position in **seconds**). |
| `add(track \| tracks, insertBeforeIndex?)` | Append, or insert before an index. |
| `load(track)` | Replace the active track (or start the queue if empty). |
| `move(from, to)` | Reorder; `to` past the end → end. |
| `remove(index \| indexes)` | Remove track(s); active removal advances. |
| `removeUpcomingTracks()` | Drop everything after the active track. |
| `skip(index, initialPosition?)` | Jump to a track (position in **seconds**). |
| `skipToNext()` / `skipToPrevious()` | Step between tracks. |
| `useQueue()` / `getQueue()` / `getTrack(i)` | Read the queue. |
| `useActiveTrack()` / `getActiveTrack()` / `getActiveTrackIndex()` | Read the active track. |
| `onActiveTrackChanged` / `onQueueChanged` / `onQueueEnded` | Subscribe to changes. |
| `setRepeatMode(mode)` / `useRepeatMode()` | `'off'` / `'track'` / `'queue'`. |
| `setShuffle(bool)` / `toggleShuffle()` / `useShuffle()` | Shuffle order. |
