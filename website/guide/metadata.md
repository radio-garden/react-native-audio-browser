# Metadata

As a track plays, the player surfaces metadata it reads *from the media* — the
live "now playing song" of a radio stream, the tags embedded in an audio file,
and chapter markers. These arrive as three independent event streams:

- [`onTimedMetadata`](/api/features/metadata/#ontimedmetadata) — the live song
  from a stream (ICY radio or HLS in-band ID3), changing as the broadcast does.
- [`onTrackMetadata`](/api/features/metadata/#ontrackmetadata) — the static tags
  baked into a media file, read when it loads.
- [`onChapterMetadata`](/api/features/metadata/#onchaptermetadata) — chapter
  markers with time ranges, for podcasts and audiobooks.

These are **read-only signals coming *in*** from the media — they don't change
what's displayed anywhere. It's up to you to do something with them: feed the
[Now Playing](/guide/now-playing) surfaces, render your own UI, or build a
chapter list. (Each is subscription-only — there's no getter or hook; you
subscribe and keep the latest value yourself, as shown below.)

The UI snippets below import `useEffect` / `useState` from `react`, and `Text` /
`Button` / `Image` / `FlatList` from `react-native`; those import lines are
omitted for brevity. Everything else comes from `react-native-audio-browser`.

## Subscribing

Every metadata event follows the same shape: `addListener` registers a callback
and returns a cleanup function that unsubscribes.

```ts
import { onTimedMetadata } from 'react-native-audio-browser'

const unsubscribe = onTimedMetadata.addListener((metadata) => {
  console.log('now playing:', metadata.title)
})

// later:
unsubscribe()
```

Because the cleanup function is exactly what React's `useEffect` wants returned,
turning any of these into reactive state is a one-liner — see
[Holding metadata in state](#holding-metadata-in-state).

## Timed metadata — the live song

[`onTimedMetadata`](/api/features/metadata/#ontimedmetadata) delivers the
"now playing song" announced by a live stream. Two formats arrive through this
one event:

- **ICY** metadata — Shoutcast / Icecast HTTP radio.
- **In-band ID3** timed metadata — how **HLS** live streams (and some others)
  carry the current song.

It fires each time the stream announces a new song, so the value changes
throughout a single track.

[`TimedMetadata`](/api/features/metadata/#timedmetadata) — every field optional:

| Field | Meaning |
| --- | --- |
| `title` | The song title (often "Artist - Song" in ICY). |
| `artist` | The artist, when the stream sends it separately. |
| `album` | The album, when present. |
| `date` | A date string, when present. |
| `genre` | The genre, when present. |

::: warning ICY sends only a title
Shoutcast / Icecast (ICY) radio populates **only `title`** — typically a combined
`"Artist - Song"` string. `artist`, `album`, `date`, and `genre` stay `undefined`
for ICY; they're filled only by ID3 / HLS / file metadata. For a radio app,
parse `title` yourself rather than binding `metadata.artist`.
:::

This is the signal behind a live station's "now playing" line. To show it on the
lock screen / car, you usually don't subscribe here directly — you read it inside
the Now Playing formatter, which is re-invoked on every timed-metadata update.
See [Now Playing → the formatter](/guide/now-playing#the-formatter-derived-continuous).
Subscribe to `onTimedMetadata` when you want the live song for *your own* UI.

## Track metadata — the file's tags

[`onTrackMetadata`](/api/features/metadata/#ontrackmetadata) delivers the static
metadata baked into a media file — ID3 tags on an MP3, the metadata atoms in an
MP4/M4A — read when the player loads the asset. Use it for on-demand content
(podcast episodes, audiobooks, downloaded files) whose tags you didn't set
yourself.

[`TrackMetadata`](/api/features/metadata/#trackmetadata) is richer than the
timed shape. **Every field is an optional `string`:**

| Field | Meaning |
| --- | --- |
| `title` | Track title. |
| `artist` | Track artist. |
| `albumTitle` | Album name. |
| `subtitle` | Secondary line. |
| `description` | Long description / show notes. |
| `artworkUri` | Artwork URL. |
| `trackNumber` | Track number within the album. |
| `composer` | Composer credit. |
| `conductor` | Conductor credit. |
| `genre` | Genre. |
| `compilation` | Compilation name. |
| `station` | Station/channel name. |
| `mediaType` | Media type string. |
| `creationDate` | Full creation date. |
| `creationYear` | Creation year. |
| `url` | A URL associated with the track. |

```tsx
import { onTrackMetadata, type TrackMetadata } from 'react-native-audio-browser'

function FileInfo() {
  const [meta, setMeta] = useState<TrackMetadata>()
  useEffect(() => onTrackMetadata.addListener(setMeta), [])
  if (!meta) return null
  return (
    <>
      {meta.artworkUri && <Image source={{ uri: meta.artworkUri }} />}
      <Text>{meta.title} — {meta.artist}</Text>
      <Text>{meta.description}</Text>
    </>
  )
}
```

::: tip Mind the field names
Two traps when moving between the streams:
- **title vs title:** `TimedMetadata.title` is the *song* (changes during a live
  stream); `TrackMetadata.title` is the *file's* title (static, read once).
- **album vs albumTitle:** timed metadata uses `album`; track metadata uses
  `albumTitle`. And `TimedMetadata` has no artwork field — only `TrackMetadata`
  carries `artworkUri`.
:::

## Chapter metadata — podcast & audiobook chapters

[`onChapterMetadata`](/api/features/metadata/#onchaptermetadata) delivers the
full list of chapters for the current track as a
[`ChapterMetadata[]`](/api/features/metadata/#chaptermetadata):

| Field | Type | Meaning |
| --- | --- | --- |
| `startTime` | `number` | Chapter start, in **seconds**. |
| `endTime` | `number` | Chapter end, in **seconds**. |
| `title` | `string?` | Chapter title, if present. |
| `url` | `string?` | A URL associated with the chapter, if present. |

Because `startTime` is in seconds, it drops straight into
[`seekTo`](/guide/playback#seeking) to make a tappable chapter list:

```tsx
import {
  onChapterMetadata,
  seekTo,
  type ChapterMetadata
} from 'react-native-audio-browser'

function ChapterList() {
  const [chapters, setChapters] = useState<ChapterMetadata[]>([])
  useEffect(() => onChapterMetadata.addListener(setChapters), [])

  return (
    <FlatList
      data={chapters}
      keyExtractor={(chapter) => String(chapter.startTime)}
      renderItem={({ item }) => (
        <Button
          title={item.title ?? 'Chapter'}
          onPress={() => seekTo(item.startTime)}
        />
      )}
    />
  )
}
```

## Which metadata is which

| | `onTimedMetadata` | `onTrackMetadata` | `onChapterMetadata` |
| --- | --- | --- | --- |
| Source | Live stream (ICY / ID3) | Tags in the media file | Chapter markers in the file |
| Changes | Throughout the track | Once, on load | Once, on load |
| Shape | One `TimedMetadata` | One `TrackMetadata` | A `ChapterMetadata[]` |
| Typical use | Live "now playing" song | Podcast/file info | Chapter navigation |

## Holding metadata in state

None of these come with a built-in hook, but each `addListener` returns the
cleanup function `useEffect` expects, so a reusable hook is a few lines:

```tsx
import { onTimedMetadata, type TimedMetadata } from 'react-native-audio-browser'

// Returns TimedMetadata | undefined (undefined until the first event).
function useTimedMetadata(): TimedMetadata | undefined {
  const [metadata, setMetadata] = useState<TimedMetadata>()
  useEffect(() => onTimedMetadata.addListener(setMetadata), [])
  return metadata
}

function NowPlayingSong() {
  const song = useTimedMetadata()
  return <Text>{song?.title ?? 'Unknown'}</Text>
}
```

The same pattern works for `onTrackMetadata` and `onChapterMetadata` — swap the
event and the state type.

## API summary

| API | Delivers |
| --- | --- |
| `onTimedMetadata` | Live `TimedMetadata` (the streaming "now playing" song). |
| `onTrackMetadata` | Static `TrackMetadata` (the media file's embedded tags). |
| `onChapterMetadata` | `ChapterMetadata[]` (chapter markers, times in seconds). |

All three are subscriptions: `addListener(cb)` returns an unsubscribe function.
