import type {
  BrowserSource,
  ResolvedTrack,
  Track,
} from 'react-native-audio-browser'

const BASE = 'https://archive.org'

// --- Internal types for IA API responses ---

interface IASearchDoc {
  identifier: string
  title?: string
  creator?: string | string[]
  description?: string
  downloads?: number
}

interface IASearchResponse {
  response: { docs: IASearchDoc[] }
}

interface IAFile {
  name: string
  format?: string
  title?: string
  track?: string
  length?: string
  artist?: string
  album?: string
  creator?: string
}

interface IAItemMetadata {
  metadata: {
    identifier: string
    title?: string
    creator?: string | string[]
    description?: string
  }
  files: IAFile[]
}

// --- Helpers ---

function parseDuration(length: string | undefined): number | undefined {
  if (!length) return undefined
  // Pure numeric string (seconds)
  const asNum = Number(length)
  if (!isNaN(asNum)) return Math.round(asNum)
  // HH:MM:SS or MM:SS
  const parts = length.split(':').map(Number)
  if (parts.some(isNaN)) return undefined
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2]
  if (parts.length === 2) return parts[0] * 60 + parts[1]
  return undefined
}

function parseTrackNumber(track: string | undefined): number {
  if (!track) return Infinity
  // "01/12" → 1, "01" → 1
  const n = parseInt(track.split('/')[0], 10)
  return isNaN(n) ? Infinity : n
}

const AUDIO_FORMATS_PREFERRED = [
  'VBR MP3',
  '128Kbps MP3',
  '64Kbps MP3',
  'MP3',
  'Ogg Vorbis',
]

function filterAudioFiles(files: IAFile[]): IAFile[] {
  // Group files by base name (without extension) to pick best format per track
  const byBase = new Map<string, IAFile[]>()
  for (const f of files) {
    if (!f.format || !AUDIO_FORMATS_PREFERRED.some((fmt) => f.format!.includes(fmt))) continue
    const base = f.name.replace(/\.[^.]+$/, '')
    const group = byBase.get(base) ?? []
    group.push(f)
    byBase.set(base, group)
  }

  // For each base name, pick the best format
  const result: IAFile[] = []
  for (const [, group] of byBase) {
    let best: IAFile | undefined
    for (const fmt of AUDIO_FORMATS_PREFERRED) {
      best = group.find((f) => f.format!.includes(fmt))
      if (best) break
    }
    if (best) result.push(best)
  }
  return result
}

function cleanFilename(name: string): string {
  return name
    .replace(/\.[^.]+$/, '') // strip extension
    .replace(/_/g, ' ') // underscores to spaces
    .replace(/^\d+[\s.\-_]*/, '') // strip leading track numbers
    .trim()
}

function creatorString(creator: string | string[] | undefined): string | undefined {
  if (!creator) return undefined
  return Array.isArray(creator) ? creator.join(', ') : creator
}

// --- Exported functions ---

export function fetchCollections(prefix = '/archive'): ResolvedTrack {
  return {
    url: prefix,
    title: 'Archive',
    children: [
      {
        title: 'LibriVox Audiobooks',
        url: `${prefix}/collection/librivoxaudio`,
        artwork: `${BASE}/services/img/librivoxaudio`,
        style: 'grid' as const,
      },
      {
        title: 'Folksoundomy',
        url: `${prefix}/folksoundomy`,
        artwork: `${BASE}/services/img/folksoundomy`,
        style: 'grid' as const,
      },
    ],
  }
}

const FOLKSOUNDOMY_PICKS = [
  'cratediggers',
  'folksoundomy_music',
  'folksoundomy_historical',
  'folksoundomy_gamesoundtracks',
  'folksoundomy_comedy',
  'folksoundomy_effects',
  'iuma-archive',
  'dnalounge',
  'hiphopmixtapes',
  'tvtunes',
]

export async function fetchFolksoundomy(prefix = '/archive'): Promise<ResolvedTrack> {
  const params = new URLSearchParams({
    q: `collection:folksoundomy AND mediatype:collection`,
    output: 'json',
    rows: '100',
    'fl[]': 'identifier,title',
    'sort[]': 'downloads desc',
  })

  const res = await fetch(`${BASE}/advancedsearch.php?${params}`)
  if (!res.ok) throw new Error(`Archive.org search failed: ${res.status}`)
  const data: IASearchResponse = await res.json()

  // Show hand-picked collections first, then the rest
  const picked = new Set(FOLKSOUNDOMY_PICKS)
  const docs = data.response.docs
  const sorted = [
    ...FOLKSOUNDOMY_PICKS.map((id) => docs.find((d) => d.identifier === id)).filter(Boolean) as IASearchDoc[],
    ...docs.filter((d) => !picked.has(d.identifier)),
  ]

  return {
    url: `${prefix}/folksoundomy`,
    title: 'Folksoundomy',
    children: sorted.map((doc) => ({
      title: doc.title ?? doc.identifier,
      url: `${prefix}/collection/${doc.identifier}`,
      artwork: `${BASE}/services/img/${doc.identifier}`,
      style: 'grid' as const,
    })),
  }
}

export async function searchCollection(id: string, prefix = '/archive'): Promise<ResolvedTrack> {
  const params = new URLSearchParams({
    q: `mediatype:audio AND collection:${id}`,
    output: 'json',
    rows: '50',
    'sort[]': 'downloads desc',
    'fl[]': 'identifier,title,creator,description,downloads',
  })

  const [searchRes, metaRes] = await Promise.all([
    fetch(`${BASE}/advancedsearch.php?${params}`),
    fetch(`${BASE}/metadata/${id}/metadata`),
  ])
  if (!searchRes.ok) throw new Error(`Archive.org search failed: ${searchRes.status}`)
  const data: IASearchResponse = await searchRes.json()
  const meta = metaRes.ok ? await metaRes.json() as { title?: string } : null

  const children: Track[] = data.response.docs.map((doc) => ({
    title: doc.title ?? doc.identifier,
    artist: creatorString(doc.creator),
    url: `${prefix}/item/${doc.identifier}`,
    artwork: `${BASE}/services/img/${doc.identifier}`,
  }))

  return {
    url: `${prefix}/collection/${id}`,
    title: meta?.title ?? id,
    children,
  }
}

export async function fetchItem(id: string, prefix = '/archive'): Promise<ResolvedTrack> {
  const res = await fetch(`${BASE}/metadata/${id}`)
  if (!res.ok) throw new Error(`Archive.org metadata failed: ${res.status}`)
  const data: IAItemMetadata = await res.json()

  const audioFiles = filterAudioFiles(data.files)
    .sort((a, b) => parseTrackNumber(a.track) - parseTrackNumber(b.track))

  const artwork = `${BASE}/services/img/${id}`
  const albumTitle = data.metadata.title ?? id
  const albumArtist = creatorString(data.metadata.creator)

  const children: Track[] = audioFiles.map((f) => ({
    title: f.title || cleanFilename(f.name),
    src: `${BASE}/download/${id}/${encodeURIComponent(f.name)}`,
    duration: parseDuration(f.length),
    artist: f.artist || f.creator || albumArtist,
    album: f.album || albumTitle,
    artwork,
  }))

  return {
    url: `${prefix}/item/${id}`,
    title: albumTitle,
    artist: albumArtist,
    artwork,
    children,
  }
}

export const archiveRoutes: Record<string, BrowserSource> = {
  '/archive': () => Promise.resolve(fetchCollections()),
  '/archive/folksoundomy': () => fetchFolksoundomy(),
  '/archive/collection/{id}': ({ routeParams }) =>
    searchCollection(routeParams!.id),
  '/archive/item/{id}': ({ routeParams }) => fetchItem(routeParams!.id),
}

export const archiveLibraryEntry: Track = {
  title: 'Archive.org',
  url: '/archive',
  imageRow: [
    {
      title: 'LibriVox Audiobooks',
      url: '/archive/collection/librivoxaudio',
      artwork: `${BASE}/services/img/librivoxaudio`,
    },
    {
      title: 'Cratediggers',
      url: '/archive/collection/cratediggers',
      artwork: `${BASE}/services/img/cratediggers`,
    },
    {
      title: 'Folksoundomy',
      url: '/archive/folksoundomy',
      artwork: `${BASE}/services/img/folksoundomy`,
    },
  ],
}

export async function searchArchive(query: string, prefix = '/archive'): Promise<Track[]> {
  const params = new URLSearchParams({
    q: `mediatype:audio AND (title:("${query}") OR creator:("${query}"))`,
    output: 'json',
    rows: '20',
    'sort[]': 'downloads desc',
    'fl[]': 'identifier,title,creator',
  })

  const res = await fetch(`${BASE}/advancedsearch.php?${params}`)
  if (!res.ok) return []
  const data: IASearchResponse = await res.json()

  return data.response.docs.map((doc) => ({
    title: doc.title ?? doc.identifier,
    artist: creatorString(doc.creator),
    url: `${prefix}/item/${doc.identifier}`,
    artwork: `${BASE}/services/img/${doc.identifier}`,
  }))
}
