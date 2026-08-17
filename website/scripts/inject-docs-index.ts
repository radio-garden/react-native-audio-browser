import { readdirSync, readFileSync, statSync, writeFileSync } from 'fs'
import { join } from 'path'

/**
 * Prepend a pointer to `llms.txt` to every Markdown page in the build output.
 *
 * A page fetched on its own arrives context-free: an agent reading `queue.md`
 * has no way to know `playback.md` exists. The banner gives it the index in one
 * line, and reaches every route into the Markdown — a direct fetch, the "Copy
 * page" and download buttons, and the "Open in Claude / ChatGPT" links, all of
 * which serve these files verbatim.
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

// Two trailing spaces are Markdown's hard line break, so the three lines stay
// three lines inside the blockquote.
const banner = [
  '> Documentation index  ',
  `> Every page of these docs, as Markdown: ${indexUrl}  `,
  '> Use it to find the pages you need before exploring further.'
].join('\n')

// The generated pages open with frontmatter (`url`, `description`). The banner
// belongs after it, above the h1 — frontmatter is metadata, not content.
const frontmatter = /^---\n[\s\S]*?\n---\n/

function withBanner(content: string): string {
  const [head = ''] = content.match(frontmatter) ?? []
  const body = content.slice(head.length).replace(/^\n+/, '')

  return head ? `${head}\n${banner}\n\n${body}` : `${banner}\n\n${body}`
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
    // Idempotent, so a re-run over an existing dist doesn't stack banners.
    if (content.includes('> Documentation index')) continue

    writeFileSync(path, withBanner(content))
    count++
  }

  return count
}

console.log(`Injected the docs index pointer into ${processDir(distDir)} pages`)
