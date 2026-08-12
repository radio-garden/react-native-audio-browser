import type {
  BrowserSource,
  MediaRequestConfig,
  ResolvedTrack,
  Track
} from 'react-native-audio-browser'
import { Platform } from 'react-native'

const sf = (name: string, bg: string) =>
  Platform.select({ ios: `sf:${name}?bg=${bg}&fg=#fff` })

const playlists: Record<string, ResolvedTrack> = {
  'independent-sounds': {
    title: 'Independent Sounds',
    url: '/playlist/independent-sounds',
    children: [
      {
        title: 'Radio is a Foreign Country',
        src: 'b35yEqjv',
        live: true,
        artwork: sf('airplane', '#0A84FF')
      },
      {
        title: 'NTS 1',
        src: 'wT9JJD4j',
        live: true,
        artwork: sf('hare.fill', '#FF2D55')
      },
      {
        title: 'Worldwide FM',
        src: '/rg/vfm-z7pR',
        live: true,
        artwork: sf('globe.americas.fill', '#BF5AF2')
      },
      {
        title: 'Kiosk Radio',
        src: '/rg/rTzlLOJp',
        live: true,
        artwork: sf('cup.and.saucer.fill', '#FF9F0A')
      },
      {
        title: 'Rinse France',
        src: '/rg/39GkuKiS',
        live: true,
        artwork: sf('drop.fill', '#00C7BE')
      },
      {
        title: 'Radio 80000',
        src: '/rg/MBWk5Fmi',
        live: true,
        artwork: sf('flame.fill', '#FF375F')
      },
      {
        title: 'Foundation FM',
        src: '/rg/QgsEUvYo',
        live: true,
        artwork: sf('tortoise.fill', '#0A84FF')
      },
      {
        title: 'Dublin Digital Radio',
        src: '/rg/Bv4OzWTA',
        live: true,
        artwork: sf('ladybug.fill', '#30D158')
      },
      {
        title: 'LYL Radio',
        src: '/rg/LINZ0-LZ',
        live: true,
        artwork: sf('heart.circle.fill', '#FF2D55')
      }
    ]
  },
  'energetic-rhythms': {
    title: 'Energetic Rhythms',
    url: '/playlist/energetic-rhythms',
    children: [
      {
        title: 'Noods Radio',
        src: '/rg/TdAjNy_3',
        live: true,
        artwork: sf('fish.fill', '#FF9F0A')
      },
      {
        title: 'Systrum Sistum - SSR2',
        src: '/rg/ftR_mtxU',
        live: true,
        artwork: sf('lizard.fill', '#32D74B')
      },
      {
        title: 'Radio.D59B',
        src: '/rg/GSLfbwH8',
        live: true,
        artwork: sf('bolt.heart.fill', '#BF5AF2')
      },
      {
        title: 'Dublab DE',
        src: '/rg/IbYQwskl',
        live: true,
        artwork: sf('sun.max.fill', '#FFD60A')
      },
      {
        title: 'Operator Radio',
        src: '/rg/8Ls6E7wH',
        live: true,
        artwork: sf('headphones', '#5E5CE6')
      },
      {
        title: 'datafruits',
        src: '/rg/nED7EFV4',
        live: true,
        artwork: sf('carrot.fill', '#30D158')
      }
    ]
  }
}

export const radioGardenMediaTransform: MediaRequestConfig['transform'] =
  async (request) => {
    if (request.path && request.path.startsWith('/rg/')) {
      return {
        baseUrl: 'https://radio.garden/api/ara/content/listen',
        path: `${request.path.replace('/rg/', '')}/channel.mp3`
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
      url: `/playlist/${id}`
    }))
  },
  '/playlist/{id}': async ({ routeParams }) => playlists[routeParams!.id]!
}

export const radioGardenLibraryEntry: Track = {
  title: 'Radio Playlists',
  url: '/library/playlists',
  imageRow: Object.entries(playlists).map(([id, p]) => ({
    title: p.title!,
    url: `/playlist/${id}`,
    artwork: {
      'independent-sounds': sf('radio', '#FF0090'),
      'energetic-rhythms': sf('bolt.fill', '#8AC926')
    }[id]
  }))
}
