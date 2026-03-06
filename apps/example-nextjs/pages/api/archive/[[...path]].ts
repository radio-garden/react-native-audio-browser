import type { NextApiRequest, NextApiResponse } from 'next'
import {
  fetchCollections,
  fetchFolksoundomy,
  fetchItem,
  searchCollection,
} from '../../../../example-native/src/api/archive-org'

const PREFIX = '/api/archive'

export default async function handler(
  req: NextApiRequest,
  res: NextApiResponse
) {
  const segments = (req.query.path as string[] | undefined) ?? []
  const route = segments.join('/')

  try {
    if (segments.length === 0) {
      return res.json(fetchCollections(PREFIX))
    }
    if (route === 'folksoundomy') {
      return res.json(await fetchFolksoundomy(PREFIX))
    }
    if (segments[0] === 'collection' && segments[1]) {
      return res.json(await searchCollection(segments[1], PREFIX))
    }
    if (segments[0] === 'item' && segments[1]) {
      return res.json(await fetchItem(segments[1], PREFIX))
    }
    res.status(404).json({ error: 'Not Found' })
  } catch (e) {
    res.status(502).json({ error: (e as Error).message })
  }
}
