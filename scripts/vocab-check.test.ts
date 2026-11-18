import { describe, expect, it } from 'vitest'
import { check } from './vocab-check'

const glossary = (body: string) =>
  `---\ncontext: AudioBrowser\n---\n\n# Title\n\n## Group\n${body}`

describe('vocab-check', () => {
  it('accepts a glossary whose bold all names its entries', () => {
    expect(
      check(
        glossary('\n### Lane\n\nOne.\n\n### Claim\n\nA grip on a **Lane**.\n')
      )
    ).toEqual({ 'undeclared': [], 'duplicate': [], 'dead-reference': [] })
  })

  it('names a glossary that declares no context', () => {
    expect(check('# Title\n\n### Claim\n\nA grip.\n').undeclared).toHaveLength(
      1
    )
    expect(
      check('---\nowner: me\n---\n\n### Claim\n\nA grip.\n').undeclared
    ).toHaveLength(1)
  })

  it('names a term two entries both define, at the second', () => {
    expect(
      check(glossary('\n### Lane\n\nOne.\n\n### Lane\n\nTwo.\n')).duplicate
    ).toEqual(['13  Lane — first at 9'])
  })

  it('names a bold reference no entry defines, at its line', () => {
    expect(
      check(glossary('\n### Claim\n\nA grip on a **Lane**.\n'))[
        'dead-reference'
      ]
    ).toEqual(['11  Lane — no entry defines it'])
  })

  it('names the old `**Term**:` shape, which defines nothing', () => {
    expect(
      check(glossary('\n**Lane**:\nOne.\n'))['dead-reference']
    ).toHaveLength(1)
  })

  it('accepts a plural outside the mark', () => {
    expect(
      check(glossary('\n### Lane\n\nOne.\n\n### Claim\n\nOn **Lane**s.\n'))[
        'dead-reference'
      ]
    ).toEqual([])
  })

  it('names a plural inside the mark, which matches no entry', () => {
    expect(
      check(glossary('\n### Lane\n\nOn **Lanes**.\n'))['dead-reference']
    ).toEqual(['11  Lanes — no entry defines it'])
  })

  it('reads both halves of a pair heading as entries', () => {
    expect(
      check(glossary('\n### Narrow / Wide\n\nTwo.\n\nSee **Wide**.\n'))[
        'dead-reference'
      ]
    ).toEqual([])
  })

  it('reads an entry only at depth 3', () => {
    expect(
      check(glossary('\n## Lane\n\n#### Lane\n\nSee **Lane**.\n'))[
        'dead-reference'
      ]
    ).toHaveLength(1)
  })

  it('leaves `**` inside code spans and fenced code alone', () => {
    expect(
      check(
        glossary(
          '\n### Route\n\nPatterns support `*` and `**`; also `**x**`.\n\n```\n**Ghost**\n### Route\n```\n'
        )
      )
    ).toEqual({ 'undeclared': [], 'duplicate': [], 'dead-reference': [] })
  })

  it('reads bold wrapped within a paragraph, at the line it opens', () => {
    expect(
      check(glossary('\n### Lane\n\nOn a **Remote\nLane** and a **Lane**.\n'))[
        'dead-reference'
      ]
    ).toEqual(['11  Remote Lane — no entry defines it'])
  })

  it('never reads bold across a blank line', () => {
    expect(
      check(glossary('\n### Lane\n\nTwo ** stars.\n\nAnd ** more.\n'))[
        'dead-reference'
      ]
    ).toEqual([])
  })

  it('reads a file with CRLF line endings', () => {
    expect(
      check(
        glossary('\n### Lane\n\nOne.\n\n### Claim\n\nOn a **Lane**.\n').replace(
          /\n/g,
          '\r\n'
        )
      )
    ).toEqual({ 'undeclared': [], 'duplicate': [], 'dead-reference': [] })
  })

  it('closes a fence only with its own character', () => {
    expect(
      check(glossary('\n### Lane\n\n~~~\n```\n**Ghost**\n~~~\n'))[
        'dead-reference'
      ]
    ).toEqual([])
  })

  it('closes a fence only with a run at least as long and no info string', () => {
    expect(
      check(
        glossary(
          '\n### Lane\n\n````md\n```\n**Ghost**\n````\n\n```\n```ts\n**Ghost**\n```\n\nOn a **Lane**.\n'
        )
      )['dead-reference']
    ).toEqual([])
  })
})
