import { describe, expect, it } from 'vitest'
import { kindForHttpStatus, playbackErrorKind } from './playbackErrorKind'

const BAD_HTTP_STATUS = 1001
const TIMEOUT = 1003

describe('kindForHttpStatus', () => {
  it('maps statuses to kinds', () => {
    // 404/410 must be checked before the 400-499 arm swallows them.
    expect(kindForHttpStatus(404)).toBe('not-found')
    expect(kindForHttpStatus(410)).toBe('not-found')
    // Range edges.
    expect(kindForHttpStatus(400)).toBe('rejected')
    expect(kindForHttpStatus(499)).toBe('rejected')
    expect(kindForHttpStatus(500)).toBe('server-error')
    expect(kindForHttpStatus(599)).toBe('server-error')
    expect(kindForHttpStatus(302)).toBe('unknown')
    expect(kindForHttpStatus(600)).toBe('unknown')
  })
})

describe('playbackErrorKind', () => {
  it('reports offline regardless of the code', () => {
    expect(playbackErrorKind(BAD_HTTP_STATUS, 404, false)).toBe('offline')
  })

  it('uses the HTTP status Shaka carries in data[1]', () => {
    expect(playbackErrorKind(BAD_HTTP_STATUS, 503)).toBe('server-error')
  })

  it('falls back to the category when no status was reported', () => {
    // BAD_HTTP_STATUS is category 1, which is not one of the unplayable
    // categories — without a status there is nothing to classify.
    expect(playbackErrorKind(BAD_HTTP_STATUS)).toBe('unknown')
  })

  it('treats network timeouts as unreachable', () => {
    expect(playbackErrorKind(TIMEOUT)).toBe('unreachable')
  })

  it('treats media, manifest and streaming failures as unplayable', () => {
    expect(playbackErrorKind(3016)).toBe('unplayable') // MEDIA
    expect(playbackErrorKind(4032)).toBe('unplayable') // MANIFEST
    expect(playbackErrorKind(5006)).toBe('unplayable') // STREAMING
  })

  it('treats DRM failures as rejected', () => {
    expect(playbackErrorKind(6001)).toBe('rejected')
  })

  it('leaves codes that name no cause unknown', () => {
    expect(playbackErrorKind(7001)).toBe('unknown') // PLAYER
    expect(playbackErrorKind(-1)).toBe('unknown') // our own non-Shaka fallback
  })
})
