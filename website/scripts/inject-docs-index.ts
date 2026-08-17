import { readdirSync, readFileSync, statSync, writeFileSync } from 'fs'
import { join } from 'path'

/**
 * Add an `index` key to the frontmatter of every Markdown page in the build
 * output, pointing at `llms.txt`.
 *
 * A page fetched on its own arrives context-free: an agent reading `queue.md`
 * has no way to know `playback.md` exists. This reaches every route into the
 * Markdown — a direct fetch, the "Copy page" and download buttons, and the
 * "Open in Claude / ChatGPT" links, all of which serve these files verbatim.
 *
 * It sits in the frontmatter beside the `url` the plugin already writes, since
 * that's what it is: metadata about the page, not part of the page.
 *
 * Done here rather than in the sources so it stays out of `llms-full.txt`,
 * where 52 copies of "go find the index" would be noise — that file already is
 * the whole corpus.
 */

const distDir = './.vitepress/dist'

// Mirrors .vitepress/config.ts: the canonical origin for the real deploy, and a
// base-relative link for a share or preview build, which has its own llms.txt
// and no way to know its own origin at build time.
const noindex = process.env.DOCS_NOINDEX === '1'
const rawBase = process.env.DOCS_BASE ?? '/'
const base = `/${rawBase.replace(/^\/+|\/+$/g, '')}/`.replace('//', '/')
const indexUrl = noindex
  ? `${base}llms.txt`
  : 'https://audiobrowser.dev/llms.txt'

const key = `index: ${indexUrl}`

// Every generated page opens with frontmatter carrying at least `url`, but
// handle its absence rather than silently skipping the page.
const frontmatter = /^---\n[\s\S]*?\n---\n/

function withIndexKey(content: string): string {
  const [head] = content.match(frontmatter) ?? []

  if (!head) return `---\n${key}\n---\n\n${content}`

  // Appended as the last key, immediately above the closing delimiter.
  return head.replace(/---\n$/, `${key}\n---\n`) + content.slice(head.length)
}

function processDir(dir: string): number {
  let count = 0

  for (const item of readdirSync(dir)) {
    const path = join(dir, item)

    if (statSync(path).isDirectory()) {
      count += processDir(path)
      continue
    }

    if (!item.endsWith('.md')) continue

    const content = readFileSync(path, 'utf-8')
    // Idempotent, so a re-run over an existing dist doesn't stack keys. Scoped
    // to the frontmatter block so an "index:" line in prose can't match.
    const [head = ''] = content.match(frontmatter) ?? []
    if (head.includes('\nindex: ')) continue

    writeFileSync(path, withIndexKey(content))
    count++
  }

  return count
}

console.log(`Injected the docs index pointer into ${processDir(distDir)} pages`)
