import type { NextApiRequest, NextApiResponse } from 'next'

const root = {
  path: '/api',
  title: 'Example JSON Api',
  children: [
    {
      title: 'Archive.org',
      subtitle: 'Browse free audio from the Internet Archive',
      path: '/api/archive'
    },
    {
      title: 'Errors',
      subtitle: 'Various example error responses',
      path: '/api/errors'
    }
  ]
}

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  res.status(200).json(root)
}
