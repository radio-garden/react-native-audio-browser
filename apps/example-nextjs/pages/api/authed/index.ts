import type { NextApiRequest, NextApiResponse } from 'next'
import { authedTracks } from './tracks'

export default function handler(_req: NextApiRequest, res: NextApiResponse) {
  res.status(200).json({
    path: '/api/authed',
    title: 'Auth + Redirect',
    children: [
      ...authedTracks.map((track) => ({
        src: `/api/authed/${track.id}`,
        title: track.title,
        subtitle: track.subtitle
      })),
      {
        // Same endpoint, reached by a path the media transform doesn't match,
        // so no Authorization header is attached and the request 401s. This is
        // what header-authenticated media looks like when the header can't be
        // sent — the failure the redirect follow exists to avoid on web.
        src: `/api/unauthed/${authedTracks[0].id}`,
        title: 'No Auth — 401, fails',
        subtitle: 'Same endpoint, no token attached; playback errors'
      }
    ]
  })
}
