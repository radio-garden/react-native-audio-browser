import {
  getTrackIdentity,
  notifyContentChanged,
  onFavoriteChanged,
  ResolvedTrack,
  setFavorites,
  Track
} from 'react-native-audio-browser'
import { createMMKV } from 'react-native-mmkv'

const storage = createMMKV()
let favorites: Track[] = []

// Load persisted favorites on startup
const persisted = storage.getString('favorites')
if (persisted) {
  favorites = JSON.parse(persisted) as Track[]
  // setFavorites ids are matched exactly against each track's identity
  // (id when set, else src).
  setFavorites(favorites.map(getTrackIdentity).filter(Boolean) as string[])
}

/** Call after setupPlayer() to start listening for favorite changes. */
export function setupFavorites() {
  onFavoriteChanged.addListener(({ track, favorited }) => {
    const identity = getTrackIdentity(track)
    if (identity === undefined) return
    if (favorited) {
      if (!favorites.find((t) => getTrackIdentity(t) === identity)) {
        // Strip path - the library regenerates contextual paths when browsing favorites
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const { path, ...rest } = track
        favorites.push(rest as Track)
      }
    } else {
      favorites = favorites.filter((t) => getTrackIdentity(t) !== identity)
    }
    favorites.sort((a, b) => a.title.localeCompare(b.title))
    storage.set('favorites', JSON.stringify(favorites))
    notifyContentChanged('/favorites')
  })
}

export async function fetchFavorites(): Promise<ResolvedTrack> {
  return { path: '/favorites', title: 'Favorites', children: favorites }
}
