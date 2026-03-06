import { Platform } from 'react-native'
import type {
  BrowserSource,
  MediaRequestConfig,
  ResolvedTrack,
  Track,
} from 'react-native-audio-browser'

const playlists: Record<string, ResolvedTrack> = {
  'independent-sounds': {
    title: 'Independent Sounds',
    url: '/playlist/independent-sounds',
    children: [
      { title: 'Radio is a Foreign Country', src: 'b35yEqjv', live: true },
      { title: 'NTS 1', src: 'wT9JJD4j', live: true },
      { title: 'Worldwide FM', src: '/rg/vfm-z7pR', live: true },
      { title: 'Kiosk Radio', src: '/rg/rTzlLOJp', live: true },
      { title: 'Rinse France', src: '/rg/39GkuKiS', live: true },
      { title: 'Radio 80000', src: '/rg/MBWk5Fmi', live: true },
      { title: 'Foundation FM', src: '/rg/QgsEUvYo', live: true },
      { title: 'Dublin Digital Radio', src: '/rg/Bv4OzWTA', live: true },
      { title: 'LYL Radio', src: '/rg/LINZ0-LZ', live: true },
    ],
  },
  'energetic-rhythms': {
    title: 'Energetic Rhythms',
    url: '/playlist/energetic-rhythms',
    children: [
      { title: 'Noods Radio', src: '/rg/TdAjNy_3', live: true },
      { title: 'Systrum Sistum - SSR2', src: '/rg/ftR_mtxU', live: true },
      { title: 'Radio.D59B', src: '/rg/GSLfbwH8', live: true },
      { title: 'Dublab DE', src: '/rg/IbYQwskl', live: true },
      { title: 'Operator Radio', src: '/rg/8Ls6E7wH', live: true },
      { title: 'datafruits', src: '/rg/nED7EFV4', live: true },
    ],
  },
}

export const radioGardenMediaTransform: MediaRequestConfig['transform'] =
  async (request) => {
    if (request.path && request.path.startsWith('/rg/')) {
      return {
        baseUrl: 'https://radio.garden/api/ara/content/listen',
        path: `${request.path.replace('/rg/', '')}/channel.mp3`,
      }
    }
    return request
  }

export const radioGardenRoutes: Record<string, BrowserSource> = {
  '/library/playlists': {
    url: '/library/playlists',
    title: 'Radio Playlists',
    children: Object.entries(playlists).map(([id, p]) => ({
      title: p.title!,
      url: `/playlist/${id}`,
    })),
  },
  '/playlist/{id}': async ({ routeParams }) => playlists[routeParams!.id]!,
}

export const radioGardenLibraryEntry: Track = {
  title: 'Radio Playlists',
  url: '/library/playlists',
  imageRow: Object.entries(playlists).map(([id, p]) => ({
    title: p.title!,
    url: `/playlist/${id}`,
    artwork: Platform.select({
      ios: {
        'independent-sounds': 'sf:radio?bg=#FF0090&fg=#fff',
        'energetic-rhythms': 'sf:bolt.fill?bg=#8AC926&fg=#fff',
      }[id],
    }),
  })),
}
