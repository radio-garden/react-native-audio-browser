import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs'
import { join } from 'path'

const apiDir = './api'

// TypeDoc titles module pages with the raw module path ("features/favorites").
// Rewrite them to reader-facing titles; the same names are used for the link
// text on the modules index page. URLs are untouched.
const titleOverrides: Record<string, string> = {
  'features/carConnection': 'Car',
  'types/browser': 'Browser Types',
  'types/browser-nodes': 'Browser Node Types'
}

function prettyModuleTitle(modulePath: string): string {
  const override = titleOverrides[modulePath]
  if (override) return override
  const [group, name] = modulePath.split('/')
  if (group === 'features' && name) {
    // "nowPlaying" -> "Now Playing"
    return (
      name.charAt(0).toUpperCase() + name.slice(1).replace(/([A-Z])/g, ' $1')
    )
  }
  // utils pages are identifiers (getTrackIdentity, NativeUpdatedValue) — keep verbatim
  if (group === 'utils' && name) return name
  return modulePath
}

function cleanMarkdown(content: string): string {
  // Remove "### Extends" and "### Extended by" sections (including the list that follows)
  return content
    .replace(/### Extends\n\n- [^\n]+\n\n/g, '')
    .replace(/### Extended by\n\n(?:- [^\n]+\n)+\n/g, '')
    .replace(
      /^# ((?:features|types|utils)\/[\w-]+)$/m,
      (_, p) => `# ${prettyModuleTitle(p)}`
    )
    .replace(
      /^\| \[((?:features|types|utils)\/[\w-]+)\]\(/gm,
      (_, p) => `| [${prettyModuleTitle(p)}](`
    )
}

// Fenced blocks and inline spans. Used as a split pattern, so the capture group
// keeps the code segments in the resulting array at odd indices.
const codeSegment = /(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`)/g

/**
 * Escape angle brackets around generic types written in plain prose.
 *
 * TypeDoc copies repo-root prose (CHANGELOG, CONTRIBUTING, …) into `api/_media`
 * verbatim, and VitePress compiles every page as a Vue SFC — so a generic in an
 * unbackticked changelog entry, like `Promise<RequestConfig>` from a commit
 * subject, parses as an HTML element that is never closed and fails the build.
 *
 * Only `<Capitalised>` forms are escaped: inline HTML in prose is lowercase
 * (`<br>`, `<img …>`), while generics are capitalised. Code is left alone so
 * examples keep rendering as written.
 */
function escapeGenericsInProse(content: string): string {
  return content
    .split(codeSegment)
    .map((segment, i) =>
      i % 2 === 1
        ? segment
        : segment.replace(/<(?=[A-Z][A-Za-z0-9]*>)/g, '&lt;')
    )
    .join('')
}

function processDir(dir: string) {
  for (const item of readdirSync(dir)) {
    const path = join(dir, item)
    const stat = statSync(path)

    if (stat.isDirectory()) {
      processDir(path)
    } else if (item.endsWith('.md')) {
      const content = readFileSync(path, 'utf-8')
      // TypeDoc's own output is already escaped; only the verbatim copies in
      // _media need the prose pass.
      const cleaned = path.includes('_media')
        ? escapeGenericsInProse(content)
        : cleanMarkdown(content)
      if (cleaned !== content) {
        writeFileSync(path, cleaned)
      }
    }
  }
}

processDir(apiDir)
console.log('Cleaned markdown files')
