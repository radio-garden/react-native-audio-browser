import type { NextApiRequest, NextApiResponse } from 'next'

export default function handler(_req: NextApiRequest, res: NextApiResponse) {
  res.status(200).json({
    path: '/api/redirect-probe',
    title: 'Redirect Header Probe',
    children: [
      {
        src: '/api/redirect-probe/start',
        title: 'Play me — then read the API console',
        subtitle: 'Authenticated, then redirected to a different origin'
      }
    ]
  })
}
