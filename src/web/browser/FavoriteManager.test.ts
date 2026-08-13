import { describe, expect, it } from 'vitest'
import type { Track } from '../../types'
import { trackIdentity } from '../../utils/trackIdentity'
import { FavoriteManager } from './FavoriteManager'

const withId: Track = {
  id: 'abc123',
  src: 'https://cdn.example.com/streams/abc123.mp3?token=x',
  title: 'With id'
}
const srcOnly: Track = {
  src: 'https://cdn.example.com/streams/plain.mp3',
  title: 'Src only'
}

describe('trackIdentity', () => {
  it('is the id when set', () => {
    expect(trackIdentity(withId)).toBe('abc123')
  })

  it('falls back to src when id is absent or blank', () => {
    expect(trackIdentity(srcOnly)).toBe(srcOnly.src)
    expect(trackIdentity({ id: '', src: 's' })).toBe('s')
  })

  it('is undefined for a browsable-only track', () => {
    expect(trackIdentity({ title: 'Folder' } as Track)).toBeUndefined()
  })
})

describe('FavoriteManager identity matching', () => {
  it('matches a stored id against track.id, not src', () => {
    const manager = new FavoriteManager()
    manager.setFavorites(['abc123'])
    expect(manager.hydrateFavorite(withId).favorited).toBe(true)
    // The id does not substring-match into another track's src
    expect(manager.hydrateFavorite(srcOnly).favorited).toBeUndefined()
  })

  it('matches a stored src for id-less tracks', () => {
    const manager = new FavoriteManager()
    manager.setFavorites([srcOnly.src!])
    expect(manager.hydrateFavorite(srcOnly).favorited).toBe(true)
  })

  it('does not src-match a track whose id takes identity precedence', () => {
    const manager = new FavoriteManager()
    manager.setFavorites([withId.src!])
    // identity of withId is its id, so a stored src must NOT match
    expect(manager.hydrateFavorite(withId).favorited).toBeUndefined()
  })

  it('never overwrites a caller-set favorited flag', () => {
    const manager = new FavoriteManager()
    manager.setFavorites(['abc123'])
    const preset: Track = { ...withId, favorited: false }
    expect(manager.hydrateFavorite(preset).favorited).toBe(false)
  })

  it('add/remove key the cache by identity', () => {
    const manager = new FavoriteManager()
    manager.addFavorite(withId)
    expect(manager.isFavorited(withId)).toBe(true)
    // Same identity, different src (volatile query param) still matches
    expect(
      manager.isFavorited({ ...withId, src: 'https://other.example/x.mp3' })
    ).toBe(true)
    manager.removeFavorite(withId)
    expect(manager.isFavorited(withId)).toBe(false)
  })

  it('hydrateChildren stamps favorited across a page', () => {
    const manager = new FavoriteManager()
    manager.setFavorites(['abc123'])
    const page = manager.hydrateChildren({
      path: '/list',
      title: 'List',
      children: [withId, srcOnly]
    })
    expect(page.children?.[0]?.favorited).toBe(true)
    expect(page.children?.[1]?.favorited).toBeUndefined()
  })
})
