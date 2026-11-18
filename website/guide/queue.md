---
description: 'Build and edit the queue, move between tracks with next and previous, and read it back for an "up next" UI.'
---

# Queue

The **queue** is the ordered list of tracks the player works through. The track
at the current position is the **active track**: what plays, and what
next/previous move between. This guide covers building the queue, editing it,
moving between tracks, and reading it back for an "up next" UI.

For the first-time setup and the browse-tree concept, see
[Basic Usage](/guide/basic-usage#the-queue). For transport (play / pause / seek
_within_ the active track), see [Playback](/guide/playback). The system
surfaces (lock screen, car) are in [Now Playing](/guide/now-playing).

The UI snippets below import `View` / `Text` / `Button` / `FlatList` from
`react-native`; those import lines are omitted for brevity. Everything else
comes from `react-native-audio-browser`.

## Setting the queue

[`setQueue`](/api/features/queue/#setqueue) replaces the entire queue.
Optionally pass which track to make active and where to start it:

```ts
import { setQueue, play } from 'react-native-audio-browser'

setQueue(tracks, 0) // replace; first track becomes active
setQueue(tracks, 2) // start on index 2
setQueue(tracks, 2, 30) // ...30 seconds in
```

On iOS and Android, `startIndex` defaults to 0. iOS clamps an index outside
the queue to its first or last track, and Android throws. On the web, an
omitted or out-of-range `startIndex` leaves no track active, so pass one (0 for
the first track).

`setQueue` doesn't start playback. On iOS and Android it keeps the current
play/pause state; on the web it stops playback. Either way, call
[`play()`](/api/features/playback/#play) after it (see
[Playback](/guide/playback)):

```ts
setQueue(tracks, 0) // or setQueue(tracks, startIndex)
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
queue on every add, remove, or reorder.

A favorite toggle also fires it on iOS
and Android, but on web it fires only
[`onFavoriteChanged`](/api/features/favorites/#onfavoritechanged), so listen to
both, as `useQueue` does.

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
_changes_ — e.g. for per-track analytics — subscribe to
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
import { skip, skipToNext, skipToPrevious } from 'react-native-audio-browser'

skip(3) // jump to index 3
skipToNext() // next track
skipToPrevious() // previous track
skipToNext(10) // next track, starting 10 seconds in
```

On iOS and Android, if there is no next (or previous) track, the position
still applies: the active track seeks to it. On the web the call does nothing.

This is how you move _between_ tracks
([`skip`](/api/features/queue/#skip),
[`skipToNext`](/api/features/queue/#skiptonext),
[`skipToPrevious`](/api/features/queue/#skiptoprevious)); seeking _within_ the
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
// On iOS and Android, toIndex past the end moves the track to the end;
// the web throws.

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

With shuffle on, the inserted track goes to a random place in the shuffle
order, so it may not play next.

**Removing the active track** makes the track after it active. On iOS and the
web that is the next index, wrapping to the first track, and playback carries
on.

On Android it is the next track in playback order, with shuffle and repeat
applied. If you remove the last track there, the first track becomes active,
and with repeat `'off'` playback ends.

With several indexes, Android checks them all first and throws on a repeated
one. iOS removes from the highest index down. A repeated index then also removes
the track that moved into its place, and an invalid index can throw after some
tracks are already gone. Pass each index once.

## Repeat and shuffle

[Repeat mode](/api/features/queue/#repeatmode) is one of `'off'`, `'track'`,
or `'queue'`:

| Mode      | Behavior                                                                   |
| --------- | -------------------------------------------------------------------------- |
| `'off'`   | Pause at the end of the last track; state becomes `'ended'` (the default). |
| `'track'` | Repeat the active track forever.                                           |
| `'queue'` | Loop the whole queue.                                                      |

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
    <Button title={`Repeat: ${mode}`} onPress={() => setRepeatMode(next)} />
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
[`toggleShuffle()`](/api/features/queue/#toggleshuffle) flips it. Outside
React, read the current values with
[`getRepeatMode()`](/api/features/queue/#getrepeatmode) and
[`getShuffle()`](/api/features/queue/#getshuffle), and subscribe to
[`onRepeatModeChanged`](/api/features/queue/#onrepeatmodechanged) and
[`onShuffleChanged`](/api/features/queue/#onshufflechanged).

Shuffle changes only the playback order. Indexes, `getQueue` and `useQueue`
keep list order, while `skipToNext` and `skipToPrevious` follow the shuffle
order.

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
  getTrackIdentity,
  skip,
  move,
  remove
} from 'react-native-audio-browser'

function UpNext() {
  const queue = useQueue()
  // `useActiveTrack` re-renders the list when the active track changes.
  // Compare by index: the same track can be queued twice.
  // `getActiveTrackIndex()` is read fresh on each render.
  const active = useActiveTrack()
  const activeIndex = active ? getActiveTrackIndex() : undefined

  return (
    <FlatList
      data={queue}
      keyExtractor={(track, i) => `${getTrackIdentity(track) ?? ''}:${i}`}
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
title and buttons as siblings. A key built from the track's `id` alone, or from
[`getTrackIdentity`](/api/utils/getTrackIdentity/) alone, collides when the same
track is queued twice, so the `keyExtractor` adds the index.

## A note on units

All position arguments are **seconds** — `seekTo`, `seekBy`, the
`initialPosition` on `skip` / `skipToNext` / `skipToPrevious`, and `setQueue`'s
`startPosition`.

## API summary

| API                                                                      | Purpose                                                          |
| ------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| `setQueue(tracks, startIndex?, startPosition?)`                          | Replace the queue (doesn't auto-play; position in **seconds**).  |
| `add(track \| tracks, insertBeforeIndex?)`                               | Append, or insert before an index.                               |
| `load(track)`                                                            | Replace the active track (or start the queue if empty).          |
| `move(from, to)`                                                         | Reorder; `to` past the end → end (iOS, Android; the web throws). |
| `remove(index \| indexes)`                                               | Remove track(s); see Removing the active track above.            |
| `removeUpcomingTracks()`                                                 | Drop everything after the active track.                          |
| `skip(index, initialPosition?)`                                          | Jump to a track (position in **seconds**).                       |
| `skipToNext()` / `skipToPrevious()`                                      | Step between tracks.                                             |
| `useQueue()` / `getQueue()` / `getTrack(i)`                              | Read the queue.                                                  |
| `useActiveTrack()` / `getActiveTrack()` / `getActiveTrackIndex()`        | Read the active track.                                           |
| `onActiveTrackChanged` / `onQueueChanged` / `onQueueEnded`               | Subscribe to changes.                                            |
| `setRepeatMode(mode)` / `useRepeatMode()` / `getRepeatMode()`            | `'off'` / `'track'` / `'queue'`.                                 |
| `setShuffle(bool)` / `toggleShuffle()` / `useShuffle()` / `getShuffle()` | Shuffle order.                                                   |
| `onRepeatModeChanged` / `onShuffleChanged`                               | Subscribe to repeat and shuffle changes.                         |
