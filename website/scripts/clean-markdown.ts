import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs'
import { join } from 'path'

const apiDir = './api'

function cleanMarkdown(content: string): string {
  // Remove "### Extends" and "### Extended by" sections (including the list that follows)
  return content
    .replace(/### Extends\n\n- [^\n]+\n\n/g, '')
    .replace(/### Extended by\n\n(?:- [^\n]+\n)+\n/g, '')
}

function processDir(dir: string) {
  for (const item of readdirSync(dir)) {
    const path = join(dir, item)
    const stat = statSync(path)

    if (stat.isDirectory()) {
      processDir(path)
    } else if (item.endsWith('.md')) {
      const content = readFileSync(path, 'utf-8')
      const cleaned = cleanMarkdown(content)
      if (cleaned !== content) {
        writeFileSync(path, cleaned)
      }
    }
  }
}

processDir(apiDir)
console.log('Cleaned markdown files')
