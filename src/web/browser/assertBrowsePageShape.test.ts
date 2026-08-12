import { describe, expect, it } from 'vitest'
import { assertBrowsePageShape } from './assertBrowsePageShape'

describe('assertBrowsePageShape', () => {
  it('passes a page object through unchanged', () => {
    const page = { url: '/x', title: 'X', children: [] }
    expect(assertBrowsePageShape(page, '/x')).toBe(page)
  })

  it('rejects a bare array with a shape hint', () => {
    expect(() => assertBrowsePageShape([], '/x')).toThrow(
      /page object .*children/
    )
  })

  it('rejects non-object bodies', () => {
    expect(() => assertBrowsePageShape('nope', '/x')).toThrow(
      /Browse endpoint for "\/x" returned "nope"/
    )
  })

  it('rejects null bodies', () => {
    expect(() => assertBrowsePageShape(null, '/x')).toThrow(
      /returned null.*page object/
    )
  })
})
