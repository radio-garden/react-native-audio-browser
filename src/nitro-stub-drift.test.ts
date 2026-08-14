import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * Drift guard for `ios/Model/NitroTypeStubs.swift`.
 *
 * The generated Nitro Swift types are `typealias`es onto C++ structs
 * (`margelo.nitro.audiobrowser.*`), so SwiftPM cannot compile them without the
 * whole Nitro C++ core — which is why the SPM test target substitutes
 * hand-written stubs behind `#if !canImport(NitroModules)`. The cost of that
 * trick is that the stubs can disagree with the real types, and when they do
 * `swift test` still passes while the app build breaks. That has already
 * happened once (1d2edade, "restore the SPM test-stub build after the artwork
 * variants change").
 *
 * Neither native CI job can catch it: the Swift job only ever sees the stubs,
 * and the app build only ever sees the generated types. This test is the one
 * place both files are readable at once, so the check lives here.
 *
 * The rule is subset-with-exact-types. A stub may declare FEWER properties than
 * the real type — shared `ios/` sources can only use what the stub declares, so
 * an omission is safe. What is not safe is a property the real type spells
 * differently: `var id: String` against a generated `id: String?` compiles under
 * test and fails to compile in the app.
 */
const ROOT = process.cwd()
const STUBS = join(ROOT, 'ios', 'Model', 'NitroTypeStubs.swift')
const GENERATED = join(ROOT, 'nitrogen', 'generated', 'ios', 'swift')

/**
 * Stubbed types with no generated counterpart, and why. Anything else missing
 * from `nitrogen/generated/ios/swift/` is a stub for a type that no longer
 * exists — the loudest kind of drift.
 */
const NOT_GENERATED: Record<string, string> = {
  // Nitro core type (`nitro::NullType`), shipped by NitroModules rather than
  // emitted per-project, so there is no file of ours to compare against.
  NullType: 'provided by NitroModules core'
}

type Struct = { kind: 'struct'; members: Map<string, string> }
type Enum = { kind: 'enum'; members: Map<string, string> }
type Shape = Struct | Enum

/** Splits a parameter/case list on top-level commas, ignoring `[]`, `()`, `<>`. */
function splitTopLevel(text: string): string[] {
  const parts: string[] = []
  let depth = 0
  let current = ''
  for (const char of text) {
    if (char === '[' || char === '(' || char === '<') depth++
    else if (char === ']' || char === ')' || char === '>') depth--
    if (char === ',' && depth === 0) {
      parts.push(current)
      current = ''
    } else current += char
  }
  if (current.trim()) parts.push(current)
  return parts.map((part) => part.trim()).filter(Boolean)
}

/** `label: Type` → [label, Type]. Splits on the FIRST colon (`[String: String]`). */
function splitLabelled(part: string): [string, string] | null {
  const colon = part.indexOf(':')
  if (colon < 0) return null
  const label = part.slice(0, colon).trim()
  // A generated init can carry both an argument label and a parameter name
  // (`for value: String`); the property name is the last word before the colon.
  const name = label.split(/\s+/).pop()!
  return [name, part.slice(colon + 1).trim()]
}

const DECL =
  /^\s*(?:(?:public|@frozen|indirect|final)\s+)*(struct|enum)\s+([A-Za-z_]\w*)\s*(?::[^{]*)?\{/

/** Parses every top-level `struct`/`enum` in a Swift file into its member map. */
function parseSwiftDecls(source: string): Map<string, Shape> {
  const shapes = new Map<string, Shape>()
  const lines = source.split('\n')

  for (let i = 0; i < lines.length; i++) {
    const decl = DECL.exec(lines[i]!)
    if (!decl) continue
    const keyword = decl[1]!
    const name = decl[2]!
    const members = new Map<string, string>()
    let depth = 1

    for (let j = i + 1; j < lines.length && depth > 0; j++) {
      const body = lines[j]!.replace(/\/\/.*$/, '')

      if (depth === 1) {
        // Stored property: `var name: Type`, but not a computed one (`{` after).
        const prop =
          /^\s*(?:public\s+)?var\s+([A-Za-z_]\w*)\s*:\s*([^={]+?)\s*(?:=|$)/.exec(
            body
          )
        if (prop && !/\{\s*$/.test(body)) members.set(prop[1]!, prop[2]!.trim())

        // `case a`, `case a(T)`, `case a, b`
        const enumCase = /^\s*case\s+(.+)$/.exec(body)
        if (enumCase && keyword === 'enum') {
          for (const one of splitTopLevel(enumCase[1]!)) {
            const withPayload = /^([A-Za-z_]\w*)\s*\((.*)\)$/.exec(one)
            if (withPayload)
              members.set(withPayload[1]!, withPayload[2]!.trim())
            else if (/^[A-Za-z_]\w*$/.test(one)) members.set(one, '')
          }
        }
      }

      for (const char of body) {
        if (char === '{') depth++
        else if (char === '}') depth--
      }
    }

    shapes.set(name, {
      kind: keyword === 'struct' ? 'struct' : 'enum',
      members
    } as Shape)
  }
  return shapes
}

/**
 * Reads the real shape of a generated type. Nitro emits three forms: a struct
 * (memberwise `init`), a string-backed enum (`init?(fromString:)`), and a
 * variant (a plain `enum` with `first`/`second`/… payload cases).
 */
function parseGenerated(name: string): Shape | null {
  let source: string
  try {
    source = readFileSync(join(GENERATED, `${name}.swift`), 'utf8')
  } catch {
    return null
  }

  // Variant enums are declared outright, so the generic parser sees them.
  const declared = parseSwiftDecls(source).get(name)
  if (declared && declared.members.size > 0) return declared

  // String-backed enum. Read the case names off the `stringValue` switch, which
  // spells them out (`case .get: return "GET"`), rather than deriving them from
  // the JS values — nitrogen's casing rule is not ours to reimplement.
  if (source.includes('var stringValue: String')) {
    const members = new Map<string, string>()
    for (const match of source.matchAll(/case\s+\.(\w+)\s*:/g))
      members.set(match[1]!, '')
    return { kind: 'enum', members }
  }

  const init = /^\s*init\((.*)\)\s*\{/m.exec(source)
  if (init) {
    const members = new Map<string, string>()
    for (const part of splitTopLevel(init[1]!)) {
      const labelled = splitLabelled(part)
      if (labelled) members.set(labelled[0], labelled[1])
    }
    return { kind: 'struct', members }
  }

  return null
}

/**
 * Sugared and desugared spellings are the same Swift type — nitrogen emits
 * `Dictionary<String, String>` where the stubs write `[String: String]`.
 */
function normalizeType(type: string): string {
  let previous: string
  let current = type.replace(/\s+/g, ' ').trim()
  do {
    previous = current
    current = current
      .replace(/\bDictionary<([^<>]+),\s*([^<>]+)>/g, '[$1: $2]')
      .replace(/\bArray<([^<>]+)>/g, '[$1]')
      .replace(/\bOptional<([^<>]+)>/g, '$1?')
  } while (current !== previous)
  return current
}

describe('NitroTypeStubs mirror the generated Nitro types', () => {
  const stubs = parseSwiftDecls(readFileSync(STUBS, 'utf8'))

  it('parses the stub file', () => {
    // A parser that silently matches nothing would make every check below pass.
    expect(stubs.size).toBeGreaterThan(20)
    expect(stubs.get('Track')?.members.get('src')).toBe('String?')
  })

  it('stubs only types that Nitro actually generates', () => {
    const orphans = [...stubs.keys()].filter(
      (name) => !(name in NOT_GENERATED) && parseGenerated(name) == null
    )
    expect(
      orphans,
      `Stubbed with no generated counterpart in nitrogen/generated/ios/swift — the type was ` +
        `renamed or removed from the Nitro spec, so the stub is standing in for nothing:\n` +
        orphans.join('\n')
    ).toEqual([])
  })

  it('declares every stubbed property with the generated type', () => {
    const drift: string[] = []

    for (const [name, stub] of stubs) {
      if (name in NOT_GENERATED) continue
      const real = parseGenerated(name)
      if (real == null) continue // reported by the previous test

      for (const [member, stubType] of stub.members) {
        const realType = real.members.get(member)
        const what = real.kind === 'enum' ? 'case' : 'property'
        if (realType == null) {
          drift.push(
            `${name}.${member}: stub declares a ${what} the generated type does not have`
          )
        } else if (normalizeType(realType) !== normalizeType(stubType)) {
          drift.push(
            `${name}.${member}: stub \`${stubType || '(no payload)'}\`, ` +
              `generated \`${realType || '(no payload)'}\``
          )
        }
      }
    }

    expect(
      drift,
      `ios/Model/NitroTypeStubs.swift disagrees with nitrogen/generated/ios/swift. ` +
        `\`swift test\` compiles against the stub and will not catch this; the app build ` +
        `compiles against the generated type and will fail. Fix the stub to match:\n` +
        drift.join('\n')
    ).toEqual([])
  })

  it('declares struct members in the generated initializer order', () => {
    // Name/type parity is not enough: the stub struct's memberwise init must
    // accept the same ARGUMENT ORDER as the generated labelled init, or shared
    // sources compile under `swift test` and fail in the app build with
    // "argument 'x' must precede argument 'y'" (nitrogen flattens `extends`
    // own-properties-first, which is easy to get backwards — SectionStyle did).
    // A stub may omit members; the ones it declares must be a subsequence of
    // the generated order.
    const misordered: string[] = []

    for (const [name, stub] of stubs) {
      if (name in NOT_GENERATED || stub.kind !== 'struct') continue
      const real = parseGenerated(name)
      if (real == null || real.kind !== 'struct') continue

      const realOrder = [...real.members.keys()]
      const stubOrder = [...stub.members.keys()].filter((member) =>
        real.members.has(member)
      )
      let cursor = -1
      for (const member of stubOrder) {
        const index = realOrder.indexOf(member)
        if (index < cursor) {
          misordered.push(
            `${name}: stub declares [${stubOrder.join(', ')}], ` +
              `generated init order is [${realOrder.join(', ')}]`
          )
          break
        }
        cursor = index
      }
    }

    expect(
      misordered,
      `Stub struct members are out of order relative to the generated init:\n` +
        misordered.join('\n')
    ).toEqual([])
  })
})
