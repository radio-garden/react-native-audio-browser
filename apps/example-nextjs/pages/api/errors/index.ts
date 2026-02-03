import type { NextApiRequest, NextApiResponse } from 'next'

export const errors = [
  [
    503,
    {
      src: '/api/errors/broken.mp3',
      title: 'Broken Track Endpoint (503)'
    }
  ],
  [
    402,
    {
      url: '/api/errors/premium',
      title: 'Premium Music (402)',
      error: 'Payment Required'
    }
  ],
  [
    403,
    {
      url: '/api/errors/forbidden',
      title: 'Forbidden Content (403)'
    }
  ],
  [
    401,
    {
      url: '/api/errors/auth-expired',
      title: 'Auth Expired (401)'
    }
  ],
  [
    451,
    {
      url: '/api/errors/geo-blocked',
      title: 'Geo-blocked (451)'
    }
  ],
  [
    429,
    {
      url: '/api/errors/rate-limited',
      title: 'Rate Limited (429)'
    }
  ]
] as const

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  res.status(200).json({
    url: '/api/errors',
    title: 'Errors',
    children: errors.map(([, track]) => track)
  })
}
