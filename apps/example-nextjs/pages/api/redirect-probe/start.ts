import type { NextApiRequest, NextApiResponse } from 'next'
import { logHop } from './log'

/**
 * Hop 1: records whether the player sent the credential, then redirects to a
 * different origin — the same reversed port, but `127.0.0.1` not `localhost`.
 */
export default function handler(req: NextApiRequest, res: NextApiResponse) {
  logHop(1, {
    host: req.headers.host,
    authorization: req.headers.authorization,
    userAgent: req.headers['user-agent']
  })

  const port = process.env.EXAMPLE_API_PORT ?? '3003'
  res.setHeader('Cache-Control', 'no-store, private')
  res.redirect(302, `http://127.0.0.1:${port}/api/redirect-probe/target`)
}
