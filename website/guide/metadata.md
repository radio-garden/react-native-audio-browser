---
description: 'Read the metadata coming out of the media as it plays: the live ICY or HLS song, the static tags baked into a file, and chapter markers.'
---

# Metadata

As a track plays, the player surfaces metadata it reads _from the media_ — the
live "now playing song" of a radio stream, the tags embedded in an audio file,
and chapter markers. These arrive as three independent event streams:

- [`onTimedMetadata`](/api/features/metadata/#ontimedmetadata) — the live song
  from a stream (ICY radio or HLS in-band ID3), changing as the broadcast does.
- [`onTrackMetadata`](/api/features/metadata/#ontrackmetadata) — the track's
  static metadata: on iOS the file's own tags, read when it loads; on Android
  the player's merged view of the Track and the file.
- [`onChapterMetadata`](/api/features/metadata/#onchaptermetadata) — chapter
  markers with time ranges, for podcasts and audiobooks.

These are **read-only signals coming _in_** from the media — they don't change
what's displayed anywhere. It's up to you to do something with them: feed the
[Now Playing](/guide/now-playing) surfaces, render your own UI, or build a
chapter list.

Each is subscription-only: there's no getter or hook. You subscribe and keep the
latest value yourself, as shown below.

The UI snippets below import `useEffect` / `useState` from `react`, and `Text` /
`Button` / `FlatList` from `react-native`; those import lines are
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

## Timed metadata: the live song

[`onTimedMetadata`](/api/features/metadata/#ontimedmetadata) delivers the
"now playing song" announced by a live stream. Two formats arrive through this
one event:

- **ICY** metadata — Shoutcast / Icecast HTTP radio.
- **In-band ID3** timed metadata — how **HLS** live streams (and some others)
  carry the current song.

It fires each time the stream announces a new song, so the value changes
throughout a single track.

::: info iOS also fires it for a file's tags
On iOS, a file without chapter markers fires `onTimedMetadata` too. It fires
once on load for each metadata format the file carries (ID3, iTunes), with the
file's own tags: `title`, `artist`, `album` and so on.

The
[Now Playing formatter](/guide/now-playing#the-formatter-derived-continuous)
then gets them as `timedMetadata` for an on-demand file too. A formatter that
reads `timedMetadata.title` shows the file's title. Android fires it only for
metadata the media sends during playback.
:::

[`TimedMetadata`](/api/features/metadata/#timedmetadata) — every field optional:

| Field    | Meaning                                          |
| -------- | ------------------------------------------------ |
| `title`  | The song title (often "Artist - Song" in ICY).   |
| `artist` | The artist, when the stream sends it separately. |
| `album`  | The album, when present.                         |
| `date`   | A date string, when present.                     |
| `genre`  | The genre, when present.                         |

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

Subscribe to `onTimedMetadata` when you want the live song for _your own_ UI.

## Track metadata: the track's static fields

[`onTrackMetadata`](/api/features/metadata/#ontrackmetadata) delivers a
track's static metadata. What it carries depends on the platform:

- **iOS** reads the file's own tags (ID3 on an MP3, the metadata atoms in an
  MP4/M4A) when the player loads the asset. It fires once, and only if the file
  has tags.
- **Android** reports the player's merged metadata. The Track's own fields
  (`title`, `subtitle` as `artist`, `album`, `description`, `genre`, artwork)
  win over the file's tags. Once the [Now Playing](/guide/now-playing) line is
  rendered, its title, artist and album replace them.

  The event fires again whenever that merged view changes: a new now-playing
  line, new artwork, or stream metadata that fills a field the Track leaves
  empty.

So on Android, `title` and `artist` are what you or your formatter set, not the
file's. On iOS, use it for on-demand content (podcast episodes, audiobooks,
downloaded files) whose tags you didn't set yourself.

[`TrackMetadata`](/api/features/metadata/#trackmetadata) is richer than the
timed shape. **Every field is an optional `string`:**

| Field          | Meaning                                                                                                                                         |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `title`        | Track title.                                                                                                                                    |
| `artist`       | Track artist.                                                                                                                                   |
| `albumTitle`   | Album name.                                                                                                                                     |
| `subtitle`     | Secondary line. On Android, the now-playing artist line once it is rendered.                                                                    |
| `description`  | Long description / show notes.                                                                                                                  |
| `artworkUri`   | Android only: the artwork URL the player publishes for the Track, not the file's embedded cover art.                                            |
| `trackNumber`  | Track number within the album.                                                                                                                  |
| `composer`     | Composer credit.                                                                                                                                |
| `conductor`    | Conductor credit.                                                                                                                               |
| `genre`        | Genre.                                                                                                                                          |
| `station`      | Android only: the station name from a Shoutcast / Icecast stream's headers.                                                                     |
| `mediaType`    | Android only, usually unset: a Media3 `MEDIA_TYPE_*` constant, as a number string.                                                              |
| `creationDate` | iOS: the tag's date string (ID3 `TDRC`, the iTunes release date). Android: day and month only, as `DDMM` (or `DD` or `MM` alone), with no year. |
| `creationYear` | Creation year. On iOS, the first four characters of `creationDate` when there is no year tag.                                                   |

`compilation` and `url` are in the type but neither platform sets them. Neither
platform exposes a file's embedded cover art.

```tsx
import { onTrackMetadata, type TrackMetadata } from 'react-native-audio-browser'

function FileInfo() {
  const [meta, setMeta] = useState<TrackMetadata>()
  useEffect(() => onTrackMetadata.addListener(setMeta), [])
  if (!meta) return null
  return (
    <>
      <Text>
        {meta.title} — {meta.artist}
      </Text>
      <Text>{meta.description}</Text>
    </>
  )
}
```

::: tip Mind the field names
Two traps when moving between the streams:

- **title vs title:** `TimedMetadata.title` is the _song_ (changes during a live
  stream). `TrackMetadata.title` is the _file's_ title on iOS, and on Android
  the Track's title or the now-playing title.
- **album vs albumTitle:** timed metadata uses `album`; track metadata uses
  `albumTitle`. Neither carries the file's cover art. `TimedMetadata` has no
  artwork field, and `TrackMetadata.artworkUri` is the Track's artwork as
  Android publishes it.
  :::

## Chapter metadata: podcast & audiobook chapters

[`onChapterMetadata`](/api/features/metadata/#onchaptermetadata) delivers the
full list of chapters for the current track as a
[`ChapterMetadata[]`](/api/features/metadata/#chaptermetadata):

| Field       | Type      | Meaning                                                                         |
| ----------- | --------- | ------------------------------------------------------------------------------- |
| `startTime` | `number`  | Chapter start, in **seconds**.                                                  |
| `endTime`   | `number`  | Chapter end, in **seconds**.                                                    |
| `title`     | `string?` | Chapter title. Android falls back to the chapter's element ID (such as `chp0`). |
| `url`       | `string?` | Android only: the chapter's URL link, if present.                               |

Because Android fills an untitled chapter with its element ID, the
`item.title ?? 'Chapter'` fallback below applies only on iOS.

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

|             | `onTimedMetadata`                                                          | `onTrackMetadata`                                             | `onChapterMetadata`         |
| ----------- | -------------------------------------------------------------------------- | ------------------------------------------------------------- | --------------------------- |
| Source      | Live stream (ICY / ID3); on iOS also a file's tags                         | iOS: the file's tags. Android: the Track merged with the file | Chapter markers in the file |
| Changes     | Throughout the track; on iOS also once on load for a file without chapters | iOS: once, on load. Android: whenever the merged view changes | Once, on load               |
| Shape       | One `TimedMetadata`                                                        | One `TrackMetadata`                                           | A `ChapterMetadata[]`       |
| Typical use | Live "now playing" song                                                    | Podcast/file info                                             | Chapter navigation          |
| Platform    | iOS, Android                                                               | iOS, Android                                                  | iOS, Android                |

::: warning Not available on web
All three are iOS and Android only. On web they are subscribable but never
fire, so a listener sees nothing and
[`FormatNowPlayingParams.timedMetadata`](/api/features/player/#formatnowplayingparams)
stays `undefined`.

ICY is the hard case: the browser strips the interleaved metadata before the
player can reach it, and no API exposes it. In-band ID3 from HLS _is_ reachable
through the web player and simply isn't wired up yet — that part is tracked in
[#118](https://github.com/radio-garden/react-native-audio-browser/issues/118).
:::

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

| API                 | Delivers                                                                                     |
| ------------------- | -------------------------------------------------------------------------------------------- |
| `onTimedMetadata`   | Live `TimedMetadata` (the streaming "now playing" song; on iOS also a file's tags, on load). |
| `onTrackMetadata`   | `TrackMetadata` (iOS: the file's tags; Android: the Track merged with them).                 |
| `onChapterMetadata` | `ChapterMetadata[]` (chapter markers, times in seconds).                                     |

All three are subscriptions: `addListener(cb)` returns an unsubscribe function.
