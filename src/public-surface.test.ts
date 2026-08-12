import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * Guard on what `import { … } from 'react-native-audio-browser'` can reach.
 *
 * The package root is a chain of unfiltered `export *` barrels, so every
 * `export` in every feature file joins the public surface by default — there is
 * no gate to forget to pass, only one to forget to add.
 *
 * `@internal` alone does not keep a symbol private. It is honoured by
 * `stripInternal` when generating `lib/typescript`, which is what a consumer's
 * TypeScript sees — but React Native resolves `"react-native": "src/index"`, so
 * Metro bundles the *source*, where nothing was stripped. An `@internal` value
 * is therefore invisible to the type checker and fully reachable at runtime:
 * `require('react-native-audio-browser').nativeBrowser` used to hand out the raw
 * Nitro object, whose `on*` properties are the single callback slots the library's
 * emitters own — assigning one unsubscribes every hook in the package.
 *
 * So: values marked `@internal` must not be reachable from the barrel at all.
 * Move them to a module the barrel doesn't re-export (see
 * `features/player/validateOptions.ts`) and import them directly. Types are
 * exempt — they carry no runtime representation, and `stripInternal` genuinely
 * removes them from the published `.d.ts`.
 */
const SRC = join(process.cwd(), 'src')
const ENTRY = join(SRC, 'index.ts')

const VALUE_KINDS = new Set([
  'const',
  'let',
  'var',
  'function',
  'class',
  'enum'
])

function resolveModule(fromFile: string, spec: string): string | undefined {
  const base = resolve(dirname(fromFile), spec.replace(/\.tsx?$/, ''))
  return [`${base}.ts`, `${base}.tsx`, join(base, 'index.ts')].find(existsSync)
}

interface Export {
  name: string
  file: string
  internal: boolean
}

/** Walks the `export *` graph from `entry`, collecting every reachable value export. */
function collectValueExports(entry: string): Export[] {
  const found: Export[] = []
  const seen = new Set<string>()

  const visit = (file: string) => {
    if (seen.has(file)) return
    seen.add(file)
    const source = readFileSync(file, 'utf8')

    for (const [, spec] of source.matchAll(/^export \* from ['"](.+?)['"]/gm)) {
      const target = resolveModule(file, spec!)
      if (target) visit(target)
    }

    const declaration =
      /^export (?:declare )?(?:async )?(?:abstract )?(const|let|var|function|class|enum) (\w+)/gm
    for (const match of source.matchAll(declaration)) {
      if (!VALUE_KINDS.has(match[1]!)) continue
      // The JSDoc block, if any, is whatever sits directly above the export.
      const preceding = source.slice(0, match.index)
      const doc = /\/\*\*(?:(?!\*\/)[\s\S])*\*\/\s*$/.exec(preceding)?.[0] ?? ''
      found.push({
        name: match[2]!,
        file: relative(SRC, file),
        internal: /@internal\b/.test(doc)
      })
    }
  }

  visit(entry)
  return found
}

describe('public surface', () => {
  const exports = collectValueExports(ENTRY)

  it('reaches the feature modules at all (the walker still works)', () => {
    // Cheap canary: if the barrel structure changes shape and the walker stops
    // resolving, every other assertion here would pass vacuously.
    expect(exports.map((e) => e.name)).toContain('trackPlaybackTime')
    expect(exports.length).toBeGreaterThan(100)
  })

  it('exposes no value marked @internal', () => {
    const leaked = exports
      .filter((e) => e.internal)
      .map((e) => `${e.name}  (${e.file})`)
    expect(
      leaked,
      'These values are tagged @internal but are reachable from the package root ' +
        'at runtime — stripInternal only hides them from the published types, and ' +
        'Metro bundles src/. Move each to a module the barrels do not re-export ' +
        'and import it directly:\n' +
        leaked.join('\n')
    ).toEqual([])
  })

  it('exposes no __-prefixed test hook', () => {
    const hooks = exports
      .filter((e) => e.name.startsWith('__'))
      .map((e) => `${e.name}  (${e.file})`)
    expect(
      hooks,
      'Test-only exports ship to consumers. Drive the unit under test through ' +
        'the seam it already has (a mocked native slot, say) instead:\n' +
        hooks.join('\n')
    ).toEqual([])
  })

  it('does not re-export the raw Nitro object', () => {
    expect(exports.map((e) => e.name)).not.toContain('nativeBrowser')
  })
})
