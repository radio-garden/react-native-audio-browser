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
    },
    {
      title: 'Auth + Redirect',
      subtitle: 'Media authenticated by a header, redirected to a public file',
      path: '/api/authed'
    },
    {
      title: 'Redirect Header Probe',
      subtitle: 'Does this platform re-send the token across a redirect?',
      path: '/api/redirect-probe'
    }
  ]
}

export default function handler(_req: NextApiRequest, res: NextApiResponse) {
  res.status(200).json(root)
}
