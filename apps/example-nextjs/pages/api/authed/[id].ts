import type { NextApiRequest, NextApiResponse } from 'next'
import { findAuthedTrack } from './tracks'

/**
 * Authenticates, then redirects to a public URL — the shape entitled audio
 * usually takes. Any bearer token is accepted; the point is whether the header
 * arrives at all, not what it contains.
 */
export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const id = String(req.query.id ?? '')
  const track = findAuthedTrack(id)

  if (!track) {
    res.status(404).json({ error: `No such stream '${id}'` })
    return
  }

  const authorization = req.headers.authorization
  if (!authorization?.toLowerCase().startsWith('bearer ')) {
    // What a media element gets on web without the redirect being followed for
    // it: the request is made by the browser, which cannot attach the header.
    res.status(401).json({
      error: 'Missing bearer token',
      hint: 'Configure `media.transform` to attach an Authorization header.'
    })
    return
  }

  // Short-lived and caller-specific in a real API, so never cached or shared.
  res.setHeader('Cache-Control', 'no-store, private')
  res.setHeader('Vary', 'Authorization')
  res.redirect(302, track.url)
}
