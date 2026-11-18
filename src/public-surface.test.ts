import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * Guard on what `import { … } from 'react-native-audio-browser'` can reach.
 *
 * `@internal` hides a value from the published types but not from the bundle:
 * Metro resolves `"react-native": "src/index"` and compiles the source, where
 * `stripInternal` never ran. Types are exempt — they have no runtime form.
 * `CLAUDE.md` in this directory has the full rule.
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
      'Reachable from the package root at runtime despite @internal. Move each ' +
        'to a module the barrels skip, and import it directly:\n' +
        leaked.join('\n')
    ).toEqual([])
  })

  it('exposes no __-prefixed test hook', () => {
    const hooks = exports
      .filter((e) => e.name.startsWith('__'))
      .map((e) => `${e.name}  (${e.file})`)
    expect(
      hooks,
      'Test-only exports ship to consumers. Drive the unit through a seam it ' +
        'already has — a mocked native slot, say:\n' +
        hooks.join('\n')
    ).toEqual([])
  })

  it('does not re-export the raw Nitro object', () => {
    expect(exports.map((e) => e.name)).not.toContain('nativeBrowser')
  })

  it('every public module is covered by the typedoc entry points', () => {
    // The API reference documents entryPoints, not the export graph — a public
    // module missing from website/typedoc.json ships undocumented.
    const config = JSON.parse(
      readFileSync(join(process.cwd(), 'website', 'typedoc.json'), 'utf8')
    ) as { entryPoints: string[] }
    const entries = config.entryPoints.map(
      (glob) =>
        new RegExp(
          '^' +
            glob
              .replace(/^\.\.\/src\//, '')
              .replace(/[.]/g, '\\.')
              .replace(/\*/g, '[^/]+') +
            '$'
        )
    )
    const covered = (file: string): boolean => {
      if (entries.some((e) => e.test(file))) return true
      // A directory barrel entry (…/index.ts) documents what it re-exports.
      const dir = file.split('/').slice(0, -1)
      while (dir.length > 0) {
        if (entries.some((e) => e.test([...dir, 'index.ts'].join('/'))))
          return true
        dir.pop()
      }
      return false
    }
    const uncovered = [...new Set(exports.map((e) => e.file))].filter(
      (file) => !covered(file)
    )
    expect(
      uncovered,
      'Exported to consumers but absent from the API reference. Add each ' +
        'module (or its directory barrel) to website/typedoc.json ' +
        'entryPoints:\n' +
        uncovered.join('\n')
    ).toEqual([])
  })
})
