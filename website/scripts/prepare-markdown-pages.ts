import { readdirSync, readFileSync, statSync, writeFileSync } from 'fs'
import { join, posix } from 'path'

/**
 * Make the Markdown twins of the site standalone, for the agents that read
 * them — via a direct fetch, the "Copy page" and download buttons, or the
 * "Open in Claude / ChatGPT" links, all of which serve these files verbatim.
 *
 * Two passes:
 *
 * 1. An `index` key in the frontmatter, pointing at `llms.txt`. A page fetched
 *    on its own arrives context-free — an agent reading `queue.md` has no way
 *    to know `playback.md` exists.
 *
 * 2. Internal links rewritten to the `.md` twin, absolute. As authored they
 *    point at HTML routes (`/guide/basic-usage`), so following one drops the
 *    reader back out of Markdown; and being root-relative, they resolve
 *    against nothing once the page has been pasted somewhere else.
 *
 * Done over the build output rather than the sources so none of it reaches
 * `llms-full.txt`, which is already the whole corpus — every link there is to
 * a page in the same file.
 */

const distDir = './.vitepress/dist'

// Mirrors .vitepress/config.ts: the canonical origin for the real deploy, and
// base-relative for a share or preview build, which has its own llms.txt and no
// way to know its own origin at build time.
const canonical = 'https://audiobrowser.dev'
const noindex = process.env.DOCS_NOINDEX === '1'
const rawBase = process.env.DOCS_BASE ?? '/'
const base = `/${rawBase.replace(/^\/+|\/+$/g, '')}/`.replace('//', '/')
const origin = noindex ? base.replace(/\/$/, '') : canonical

const key = `index: ${origin}/llms.txt`

// Every generated page opens with frontmatter carrying at least `url`, but
// handle its absence rather than silently skipping the page.
const frontmatter = /^---\n[\s\S]*?\n---\n/

// Links to our own pages, in both forms they appear in:
//   ](/guide/queue)  ](/api/features/queue/#setqueue)   — as authored in guides
//   ](https://audiobrowser.dev/guide/queue)             — as authored in README
const internalLink = new RegExp(
  String.raw`\]\((?:${canonical.replace(/\./g, String.raw`\.`)})?(\/[^)\s#]*)(#[^)\s]*)?\)`,
  'g'
)

function walk(dir: string, found: string[] = []): string[] {
  for (const item of readdirSync(dir)) {
    const path = join(dir, item)

    if (statSync(path).isDirectory()) walk(path, found)
    else if (item.endsWith('.md')) found.push(path)
  }

  return found
}

const pages = walk(distDir)

// The set of pages that actually exist, as site paths. Rewriting is driven off
// this rather than off a naming rule: the plugin flattens `api/index.md` to
// `api.md` and `api/features/queue/index.md` to `api/features/queue.md`, and a
// guessed target that turned out not to exist would be a dead link.
const targets = new Set(
  pages.map((path) => `/${posix.relative(distDir, path)}`)
)

function withIndexKey(content: string): string {
  const [head] = content.match(frontmatter) ?? []

  if (!head) return `---\n${key}\n---\n\n${content}`

  // Directly under `url` — the two are the same kind of thing, and a folded
  // multi-line `description` between them makes the pair hard to read.
  const placed = head.replace(/^(---\n(?:url: .*\n)?)/, `$1${key}\n`)

  return placed + content.slice(head.length)
}

let rewritten = 0
let skipped = 0

function withMarkdownLinks(content: string): string {
  return content.replace(internalLink, (whole, path: string, hash = '') => {
    // Strip the deploy base before matching, since dist paths don't carry it.
    const sitePath = base === '/' ? path : path.replace(base, '/')
    // `/api/` and `/api/features/queue/` both flatten to a sibling .md file.
    const target = `${sitePath.replace(/\/$/, '')}.md`

    if (!targets.has(target)) {
      skipped++
      return whole
    }

    rewritten++
    return `](${origin}${target}${hash})`
  })
}

let touched = 0

for (const path of pages) {
  const content = readFileSync(path, 'utf-8')

  // Idempotent, so a re-run over an existing dist doesn't stack keys. Scoped to
  // the frontmatter block so an "index:" line in prose can't match.
  const [head = ''] = content.match(frontmatter) ?? []
  const prepared = withMarkdownLinks(
    head.includes('\nindex: ') ? content : withIndexKey(content)
  )

  if (prepared === content) continue

  writeFileSync(path, prepared)
  touched++
}

console.log(
  `Prepared ${touched} Markdown pages — ${rewritten} links rewritten, ${skipped} left alone`
)
