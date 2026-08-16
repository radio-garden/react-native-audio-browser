import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Completeness guards for the style declaration block (ADR 0011) — the checks
 * Nitro's `extends`-flattening took out of the type system, in two parts:
 * every key must be *resolved* by each platform's `StyleResolver`, and every
 * key must be *accounted for* by each platform's `InertStyleDiagnostic`.
 *
 * Part one — inheritance completeness.
 *
 * `SectionStyle extends TrackStyle` in the spec, but Nitro flattens `extends`
 * into independent structs on every platform — so nothing in any type system
 * forces a platform's `StyleResolver` to read a newly added style key. A key
 * the resolver forgets is silently dead: declared by consumers, decoded on the
 * wire, never rendered. This test is the compile-time check the flattening
 * took away:
 *
 * - every `SectionStyle` key must appear in each platform's
 *   `sectionStyle(section, page)` merge;
 * - every inherited `TrackStyle` key must appear in each platform's
 *   `trackStyle(track, section)` merge;
 * - `display` is positional (the deny-list): the track merge must resolve it
 *   to nil/null and must not read it from any level.
 */

const ROOT = process.cwd()

const RESOLVERS = [
  {
    file: 'ios/Browser/StyleResolver.swift',
    sectionMarker: 'static func sectionStyle',
    trackMarker: 'static func trackStyle',
    positionalNil: 'display: nil'
  },
  {
    file: 'android/src/main/java/com/audiobrowser/browser/StyleResolver.kt',
    sectionMarker: 'fun sectionStyle',
    trackMarker: 'fun trackStyle',
    positionalNil: 'display = null'
  }
]

/** Keys the spec deliberately excludes from item inheritance. */
const POSITIONAL = ['display']

function interfaceKeys(source: string, name: string): string[] {
  const start = source.indexOf(`export interface ${name} `)
  expect(
    start,
    `interface ${name} not found in browser-nodes.ts`
  ).toBeGreaterThan(-1)
  const end = source.indexOf('\n}', start)
  const body = source.slice(start, end)
  return [...body.matchAll(/^ {2}(\w+)\??:/gm)].flatMap((match) =>
    match[1] === undefined ? [] : [match[1]]
  )
}

/**
 * The function's text from `marker` to the next function declaration (or end
 * of file) — brace-agnostic, since Kotlin expression bodies have no braces.
 */
function functionBody(source: string, marker: string, file: string): string {
  const start = source.indexOf(marker)
  expect(start, `${marker} not found in ${file}`).toBeGreaterThan(-1)
  const rest = source.slice(start + marker.length)
  const next = rest.search(/\n\s*(?:static func|func|fun)\s/)
  return marker + (next === -1 ? rest : rest.slice(0, next))
}

/** Comment-free view, so prose about a key doesn't count as reading it. */
function stripComments(code: string): string {
  return code
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((line) => line.replace(/\/\/.*$/, ''))
    .join('\n')
}

const nodes = readFileSync(
  join(ROOT, 'src', 'types', 'browser-nodes.ts'),
  'utf8'
)
const trackKeys = interfaceKeys(nodes, 'TrackStyle')
const sectionKeys = [...trackKeys, ...interfaceKeys(nodes, 'SectionStyle')]
const inheritedKeys = trackKeys.filter((key) => !POSITIONAL.includes(key))

describe('style block shape', () => {
  it('has the positional key and at least one inherited key', () => {
    expect(trackKeys).toContain('display')
    expect(inheritedKeys.length).toBeGreaterThan(0)
  })
})

for (const resolver of RESOLVERS) {
  describe(resolver.file, () => {
    const source = readFileSync(join(ROOT, resolver.file), 'utf8')
    const sectionBody = stripComments(
      functionBody(source, resolver.sectionMarker, resolver.file)
    )
    const trackBody = stripComments(
      functionBody(source, resolver.trackMarker, resolver.file)
    )

    // Containment of `holder?.key` for BOTH holders — `display =
    // section?.display` with the page half dropped must fail — AND in
    // precedence order: the narrower holder must be read first, or the
    // scope-override/inheritance direction is inverted (`page ?? section`
    // would pass a bare containment check).
    it.each(sectionKeys)('sectionStyle merges %s (section ?? page)', (key) => {
      const section = sectionBody.indexOf(`section?.${key}`)
      const page = sectionBody.indexOf(`page?.${key}`)
      expect(section, `section?.${key} not read`).toBeGreaterThan(-1)
      expect(page, `page?.${key} not read`).toBeGreaterThan(-1)
      expect(section, `section?.${key} must precede page?.${key}`).toBeLessThan(
        page
      )
    })

    it.each(inheritedKeys)(
      'trackStyle inherits %s (track ?? section)',
      (key) => {
        const track = trackBody.indexOf(`track?.${key}`)
        const section = trackBody.indexOf(`section?.${key}`)
        expect(track, `track?.${key} not read`).toBeGreaterThan(-1)
        expect(section, `section?.${key} not read`).toBeGreaterThan(-1)
        expect(
          track,
          `track?.${key} must precede section?.${key}`
        ).toBeLessThan(section)
      }
    )

    it('trackStyle excludes the positional display (deny-list)', () => {
      expect(trackBody).toContain(resolver.positionalNil)
      // The nil assignment must be the ONLY mention: reading display from any
      // level here would item-inherit a positional key.
      const mentions = trackBody.match(/display/g) ?? []
      expect(mentions).toHaveLength(1)
    })
  })
}

/**
 * Part two — diagnostic completeness.
 *
 * The block model traded compile-time invalid-combination errors for
 * inertness, and the dev-mode `InertStyleDiagnostic` is the recovery. It is
 * exposed to the same flattening problem as the resolvers, one level up: a
 * key added to the spec has no type-system tie to the diagnostic's rule set,
 * so a property could ship whose inert combinations nobody is warned about.
 *
 * Each platform therefore dispositions the whole vocabulary — every key is a
 * `Property` with an inertness rule, `alwaysRenderable`, or `positional` —
 * and this test reads those three declarations and asserts set equality with
 * the spec, both directions: a new key must be dispositioned, and a key
 * deleted from the spec must not linger in the rule set.
 */
const DIAGNOSTICS = [
  {
    file: 'ios/Browser/InertStyleDiagnostic.swift',
    // `case gridWrap`, up to the first member after the case list.
    ruled: (source: string) =>
      keysIn(
        source,
        /enum Property[\s\S]*?var isItemProperty/,
        /^\s*case (\w+)$/gm
      )
  },
  {
    file: 'android/src/main/java/com/audiobrowser/browser/InertStyleDiagnostic.kt',
    // `GRID_WRAP("gridWrap", …)`, up to the `;` that ends the entry list.
    ruled: (source: string) =>
      keysIn(source, /enum class Property[\s\S]*?;/, /\w+\("(\w+)"/g)
  }
]

/** The capture group of every `pattern` match within the first `region`. */
function keysIn(source: string, region: RegExp, pattern: RegExp): string[] {
  const found = region.exec(source)
  expect(found, `region ${region} not found`).not.toBeNull()
  return [...found![0].matchAll(pattern)].flatMap((match) =>
    match[1] === undefined ? [] : [match[1]]
  )
}

for (const diagnostic of DIAGNOSTICS) {
  describe(diagnostic.file, () => {
    // Comment-free, so prose naming a key doesn't pass for dispositioning it.
    const source = stripComments(
      readFileSync(join(ROOT, diagnostic.file), 'utf8')
    )
    // The two lists are spelled `alwaysRenderable`/`positional` in Swift and
    // SCREAMING_CASE in Kotlin; both hold bare string literals.
    const listed = [
      ...source.matchAll(
        /(?:alwaysRenderable|ALWAYS_RENDERABLE|positional|POSITIONAL)\s*=[^\n]*/gi
      )
    ].flatMap((match) =>
      [...match[0].matchAll(/"(\w+)"/g)].map((key) => key[1])
    )

    it('dispositions every key of the block, and only those', () => {
      const dispositioned = [...diagnostic.ruled(source), ...listed]
      expect(new Set(dispositioned)).toEqual(new Set(sectionKeys))
    })
  })
}
