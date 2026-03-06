import {
  notifyContentChanged,
  onFavoriteChanged,
  ResolvedTrack,
  setFavorites,
  Track,
} from 'react-native-audio-browser'
import { createMMKV } from 'react-native-mmkv'

const storage = createMMKV()
let favorites: Track[] = []

// Load persisted favorites on startup
const persisted = storage.getString('favorites')
if (persisted) {
  favorites = JSON.parse(persisted) as Track[]
  setFavorites(favorites.map((t) => t.src).filter(Boolean) as string[])
}

/** Call after setupPlayer() to start listening for favorite changes. */
export function setupFavorites() {
  onFavoriteChanged.addListener(({ track, favorited }) => {
    if (favorited) {
      if (!favorites.find((t) => t.src === track.src)) {
        // Strip url/groupTitle - the library regenerates contextual URLs when browsing favorites
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const { url, groupTitle, ...rest } = track
        favorites.push(rest as Track)
      }
    } else {
      favorites = favorites.filter((t) => t.src !== track.src)
    }
    favorites.sort((a, b) => a.title.localeCompare(b.title))
    storage.set('favorites', JSON.stringify(favorites))
    notifyContentChanged('/favorites')
  })
}

export async function fetchFavorites(): Promise<ResolvedTrack> {
  return { url: '/favorites', title: 'Favorites', children: favorites }
}
