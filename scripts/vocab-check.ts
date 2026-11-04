#!/usr/bin/env node
/**
 * Holds GLOSSARY.md to its format: a `context:` in frontmatter, each entry a
 * `### Term` heading defined once, and every **bold** a link to an entry.
 *
 * Usage: `node scripts/vocab-check.ts [file]`, exiting 1 on any finding.
 */
import { readFileSync, realpathSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

export const FINDINGS = {
  'undeclared': 'the glossary names no context',
  'duplicate': 'a term two entries both define',
  'dead-reference': 'bold matching no entry'
} as const

export type Findings = Record<keyof typeof FINDINGS, string[]>

const FRONTMATTER = /^---\n([\s\S]*?)\n---\n/
const FENCE = /^ {0,3}(`{3,}|~{3,})(.*)$/
const ENTRY = /^### +(.+?)[ #]*$/
/** Bold may wrap within a paragraph, never across a blank line. */
const BOLD = /\*\*((?:[^*\n]|\n(?![ \t]*\n))+?)\*\*/g

/** Code spans as their bare text, so a `**` inside one is not bold. */
const withoutCode = (line: string) =>
  line.replace(/`([^`]*)`/g, (_, code: string) => code.replaceAll('*', ' '))

/** Each finding as `line  detail`, grouped by kind. */
export function check(source: string): Findings {
  const findings: Findings = {
    'undeclared': [],
    'duplicate': [],
    'dead-reference': []
  }
  const lines = source.replace(/\r\n?/g, '\n').split('\n')

  const frontmatter = FRONTMATTER.exec(lines.join('\n'))
  if (!frontmatter || !/^context:[ \t]*\S+$/m.test(frontmatter[1] ?? '')) {
    findings.undeclared.push('1  no `context:` in frontmatter')
  }

  const entries = new Map<string, number>()
  // Fenced code blanked, so line numbers stay the file's own.
  const prose: string[] = []
  let fence: string | undefined

  lines.forEach((raw, i) => {
    const marker = FENCE.exec(raw)
    if (marker) {
      const [, run = '', info = ''] = marker
      if (fence === undefined) {
        fence = run
        prose.push('')
        return
      }
      if (run[0] === fence[0] && run.length >= fence.length && !info.trim()) {
        fence = undefined
        prose.push('')
        return
      }
    }
    if (fence !== undefined) {
      prose.push('')
      return
    }
    const text = withoutCode(raw)
    prose.push(text)
    const heading = ENTRY.exec(text)
    if (heading) {
      // A heading may name a pair defined together — `### Narrow / Wide`.
      for (const name of (heading[1] ?? '').split(' / ')) {
        const first = entries.get(name)
        if (first === undefined) {
          entries.set(name, i + 1)
        } else {
          findings.duplicate.push(`${i + 1}  ${name} — first at ${first}`)
        }
      }
    }
  })

  const text = prose.join('\n')
  const references = [...text.matchAll(BOLD)].map((match) => ({
    text: (match[1] ?? '').replace(/\s+/g, ' '),
    line: text.slice(0, match.index).split('\n').length
  }))

  for (const { text, line } of references) {
    if (!entries.has(text)) {
      findings['dead-reference'].push(`${line}  ${text} — no entry defines it`)
    }
  }

  return findings
}

if (
  process.argv[1] !== undefined &&
  realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const file =
    process.argv[2] ?? fileURLToPath(new URL('../GLOSSARY.md', import.meta.url))
  const findings = check(readFileSync(file, 'utf8'))
  let total = 0
  for (const [name, meaning] of Object.entries(FINDINGS)) {
    const hits = findings[name as keyof Findings]
    total += hits.length
    for (const hit of hits) {
      console.log(`${file}:${hit}  (${name} — ${meaning})`)
    }
  }
  console.log(total === 0 ? `${file}: clean` : `${total} finding(s)`)
  process.exit(total === 0 ? 0 : 1)
}
