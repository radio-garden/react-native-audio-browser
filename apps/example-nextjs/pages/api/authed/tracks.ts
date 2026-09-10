/**
 * Public-domain audio on the Internet Archive. `archive.org/download/…`
 * redirects again to a regional server, so these walk a real multi-hop chain.
 */
export const authedTracks = [
  {
    id: 'gd77-05-08',
    title: 'Auth + Redirect — plays',
    subtitle: 'Bearer token, 302 to a public file, then it plays',
    url: 'https://archive.org/download/gd1977-05-08.shure57.stevenson.29303.flac16/gd1977-05-08d01t01.mp3'
  },
  {
    id: 'gd77-05-08-t02',
    title: 'Auth + Redirect — second track',
    subtitle: 'Same again; the token is re-sent on every load',
    url: 'https://archive.org/download/gd1977-05-08.shure57.stevenson.29303.flac16/gd1977-05-08d01t02.mp3'
  }
] as const

export const findAuthedTrack = (id: string) =>
  authedTracks.find((track) => track.id === id)
