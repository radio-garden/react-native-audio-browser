import type { NextApiRequest, NextApiResponse } from 'next'
import { errors } from './index'

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const { path } = req.query
  const fullPath = `/api/errors/${Array.isArray(path) ? path.join('/') : path}`

  const error = errors.find(
    ([, track]) =>
      ('url' in track && track.url === fullPath) ||
      ('src' in track && track.src === fullPath)
  )

  if (error) {
    const [code, body] = error
    if ('error' in body) {
      res.status(code).json({ error: body.error })
    } else {
      res.status(code).end()
    }
    return
  }

  // 404 for unknown routes
  res.status(404).json({
    error: 'Not Found',
    message: `Route ${fullPath} not found`
  })
}
