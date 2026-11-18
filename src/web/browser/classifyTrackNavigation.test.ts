import { describe, expect, it } from 'vitest'
import type { Track } from '../../types'
import { classifyTrackNavigation } from './classifyTrackNavigation'

const track = (fields: Partial<Track>): Track => fields as Track

describe('classifyTrackNavigation', () => {
  it('classifies a contextual path, exposing parent path and track id', () => {
    expect(
      classifyTrackNavigation(
        track({ path: '/library/radio?__trackId=song.mp3' })
      )
    ).toEqual({
      kind: 'contextual',
      parentPath: '/library/radio',
      trackId: 'song.mp3'
    })
  })

  it('classifies a non-contextual path as a browse navigation', () => {
    expect(classifyTrackNavigation(track({ path: '/library/radio' }))).toEqual({
      kind: 'browse'
    })
  })

  it('classifies a track with src but no path as playable', () => {
    expect(classifyTrackNavigation(track({ src: 'song.mp3' }))).toEqual({
      kind: 'playable'
    })
  })

  it('prefers browse over playable when a path is present', () => {
    expect(
      classifyTrackNavigation(
        track({ path: '/library/radio', src: 'song.mp3' })
      )
    ).toEqual({ kind: 'browse' })
  })

  it('classifies a track with neither path nor src as invalid', () => {
    expect(classifyTrackNavigation(track({}))).toEqual({ kind: 'invalid' })
  })
})
