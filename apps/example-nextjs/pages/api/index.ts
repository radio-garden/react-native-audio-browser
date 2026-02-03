import type { NextApiRequest, NextApiResponse } from 'next'

const root = {
  url: '/api',
  title: 'Example JSON Api',
  children: [
    {
      title: 'Errors',
      subtitle: 'Various example error responses',
      url: '/api/errors'
    }
  ]
}

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  res.status(200).json(root)
}
