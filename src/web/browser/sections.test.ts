import { describe, it, expect } from 'vitest'
import type { ResolvedTrack, Track } from '../../types'
import { flattenSections, normalizePage, sectionContaining } from './sections'

const track = (src: string): Track => ({ title: src, src })

describe('normalizePage', () => {
  it('wraps plain children into one untitled section', () => {
    const page: ResolvedTrack = {
      path: '/p',
      title: 'P',
      children: [track('a'), track('b')]
    }
    const normalized = normalizePage(page)
    expect(normalized.sections).toEqual([
      { children: [track('a'), track('b')] }
    ])
    expect(normalized.children).toBeUndefined()
  })

  it('prefers sections when present and strips children', () => {
    const page: ResolvedTrack = {
      path: '/p',
      title: 'P',
      sections: [{ title: 'S', children: [track('a')] }],
      children: [track('zzz')]
    }
    const normalized = normalizePage(page)
    expect(normalized.sections).toEqual([
      { title: 'S', children: [track('a')] }
    ])
    expect(normalized.children).toBeUndefined()
  })

  it('leaves a childless page without sections', () => {
    expect(normalizePage({ path: '/p', title: 'P' }).sections).toBeUndefined()
  })
})

describe('flattenSections', () => {
  it('concatenates children in section order', () => {
    const flat = flattenSections([
      { title: 'A', children: [track('a1'), track('a2')] },
      { children: [track('b1')] }
    ])
    expect(flat.map((t) => t.src)).toEqual(['a1', 'a2', 'b1'])
  })
})

describe('sectionContaining', () => {
  const sections = [
    { title: 'First', children: [track('dup'), track('x')] },
    { title: 'Second', children: [track('y'), track('dup'), track('z')] }
  ]

  it('falls back to the first section containing the identity', () => {
    const scoped = sectionContaining(sections, 'dup', undefined)
    expect(scoped?.tracks.map((t) => t.src)).toEqual(['dup', 'x'])
    expect(scoped?.tappedOffset).toBeUndefined()
  })

  it('pins the tapped section via the flat index', () => {
    // Flat index 3 = second section, offset 1.
    const scoped = sectionContaining(sections, 'dup', 3)
    expect(scoped?.tracks.map((t) => t.src)).toEqual(['y', 'dup', 'z'])
    expect(scoped?.tappedOffset).toBe(1)
  })

  it('ignores a stale index whose child no longer matches', () => {
    const scoped = sectionContaining(sections, 'dup', 1)
    expect(scoped?.tracks.map((t) => t.src)).toEqual(['dup', 'x'])
    expect(scoped?.tappedOffset).toBeUndefined()
  })

  it('ignores an out-of-range index', () => {
    const scoped = sectionContaining(sections, 'dup', 99)
    expect(scoped?.tracks.map((t) => t.src)).toEqual(['dup', 'x'])
  })

  it('returns undefined for a vanished identity', () => {
    expect(sectionContaining(sections, 'gone', 0)).toBeUndefined()
  })

  it('pins the exact copy of a within-section duplicate', () => {
    const playlist = [
      { children: [track('a'), track('b'), track('a'), track('c')] }
    ]
    const scoped = sectionContaining(playlist, 'a', 2)
    expect(scoped?.tappedOffset).toBe(2)
  })
})
