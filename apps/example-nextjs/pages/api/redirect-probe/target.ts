import type { NextApiRequest, NextApiResponse } from 'next'
import { logHop, SILENT_MP3 } from './log'

/**
 * Hop 2, on a different origin. Whether `authorization` is present here is the
 * whole question: if it is, the platform re-sent it to a host it was never
 * issued for.
 */
export default function handler(req: NextApiRequest, res: NextApiResponse) {
  logHop(2, {
    host: req.headers.host,
    authorization: req.headers.authorization,
    userAgent: req.headers['user-agent']
  })

  res.setHeader('Content-Type', 'audio/mpeg')
  res.setHeader('Content-Length', String(SILENT_MP3.length))
  res.setHeader('Accept-Ranges', 'bytes')
  res.status(200).send(SILENT_MP3)
}
