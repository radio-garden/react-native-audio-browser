import { describe, it, expect } from 'vitest'
import { SimpleRouter } from './SimpleRouter'

const router = new SimpleRouter()

function match(path: string, routes: Record<string, true>) {
  return router.findBestMatch(path, routes)
}

describe('SimpleRouter', () => {
  describe('exact matches', () => {
    it('matches a simple path', () => {
      const result = match('/artists', { '/artists': true })
      expect(result).not.toBeNull()
      expect(result![0]).toBe('/artists')
      expect(result![1].params).toEqual({})
    })

    it('matches multi-segment paths', () => {
      const result = match('/artists/top/rated', {
        '/artists/top/rated': true,
      })
      expect(result).not.toBeNull()
      expect(result![0]).toBe('/artists/top/rated')
    })

    it('returns null when no route matches', () => {
      expect(match('/unknown', { '/artists': true })).toBeNull()
    })

    it('returns null when segment count differs', () => {
      expect(match('/artists/123', { '/artists': true })).toBeNull()
    })

    it('returns null when routes are empty', () => {
      expect(match('/artists', {})).toBeNull()
    })

    it('treats trailing slashes the same as without', () => {
      const result = match('/artists/', { '/artists': true })
      expect(result).not.toBeNull()
      expect(result![0]).toBe('/artists')
    })
  })

  describe('parameter extraction', () => {
    it('extracts a single parameter', () => {
      const result = match('/artists/123', { '/artists/{id}': true })
      expect(result).not.toBeNull()
      expect(result![1].params).toEqual({ id: '123' })
    })

    it('extracts multiple parameters', () => {
      const result = match('/artists/123/albums/456', {
        '/artists/{artistId}/albums/{albumId}': true,
      })
      expect(result).not.toBeNull()
      expect(result![1].params).toEqual({
        artistId: '123',
        albumId: '456',
      })
    })
  })

  describe('single wildcard', () => {
    it('matches any single segment', () => {
      expect(match('/artists/anything', { '/artists/*': true })).not.toBeNull()
    })

    it('does not extract wildcard value into params', () => {
      const result = match('/artists/anything', { '/artists/*': true })
      expect(result![1].params).toEqual({})
    })
  })

  describe('tail wildcard', () => {
    it('matches with no remaining segments and has no tail param', () => {
      const result = match('/files', { '/files/**': true })
      expect(result).not.toBeNull()
      expect(result![1].params).toEqual({})
    })

    it('matches with remaining segments and captures tail', () => {
      const result = match('/files/a/b/c', { '/files/**': true })
      expect(result).not.toBeNull()
      expect(result![1].params).toEqual({ tail: 'a/b/c' })
    })

    it('matches with a single remaining segment', () => {
      const result = match('/files/readme.txt', { '/files/**': true })
      expect(result).not.toBeNull()
      expect(result![1].params).toEqual({ tail: 'readme.txt' })
    })

    it('returns null when prefix segments do not match', () => {
      expect(match('/other/a/b', { '/files/**': true })).toBeNull()
    })

    it('works with parameters before tail wildcard', () => {
      const result = match('/api/v1/users/list', {
        '/api/{version}/**': true,
      })
      expect(result).not.toBeNull()
      expect(result![1].params).toEqual({
        version: 'v1',
        tail: 'users/list',
      })
    })

    it('matches any path with bare /** pattern', () => {
      const result = match('/any/path/here', { '/**': true })
      expect(result).not.toBeNull()
      expect(result![1].params).toEqual({ tail: 'any/path/here' })
    })
  })

  describe('specificity', () => {
    it('prefers constant segments over parameters', () => {
      const result = match('/artists/top', {
        '/artists/{id}': true,
        '/artists/top': true,
      })
      expect(result![0]).toBe('/artists/top')
    })

    it('prefers parameters over wildcards', () => {
      const result = match('/artists/123', {
        '/artists/*': true,
        '/artists/{id}': true,
      })
      expect(result![0]).toBe('/artists/{id}')
    })

    it('prefers exact match over tail wildcard', () => {
      const result = match('/api/v1', {
        '/api/**': true,
        '/api/{version}': true,
      })
      expect(result![0]).toBe('/api/{version}')
    })

    it('computes correct specificity for tail wildcard patterns', () => {
      // Regression test for issue #27: specificity must account for
      // segment types before the ** wildcard.
      const result = match('/api/v1/users', { '/api/v1/**': true })
      // constant "api" (1000) + constant "v1" (1000) + tail(1) + 3 segments = 2004
      expect(result![1].specificity).toBe(2004)
    })

    it('prefers constant prefix over parameter prefix in tail wildcards', () => {
      // Second part of issue #27: with correct specificity, the constant
      // prefix should beat the parameter prefix.
      const result = match('/api/v1/users', {
        '/api/{version}/**': true,
        '/api/v1/**': true,
      })
      expect(result![0]).toBe('/api/v1/**')
    })
  })
})
