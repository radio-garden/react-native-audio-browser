import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs'
import { join } from 'path'
import { prefixOrder } from './base-name'

interface SidebarItem {
  text: string
  link?: string
  collapsed?: boolean
  items?: SidebarItem[]
}

interface SidebarEntry {
  name: string
  anchor: string
  subpath?: string // For folder-based modules: the file name (e.g., 'progress' for playback/progress.ts)
}

function formatModuleName(str: string): string {
  // "playbackState" -> "Playback State"
  return str.charAt(0).toUpperCase() + str.slice(1).replace(/([A-Z])/g, ' $1')
}

// Modules that should be collapsed by default
const collapsedByDefault = new Set([
  'equalizer',
  'rating',
  'favorites',
  'network',
  'battery',
  'remoteControls',
])

// Priority modules shown first (in this order), rest alphabetical
const priorityModules = ['player', 'browser', 'playback', 'errors', 'queue', 'nowPlaying']

interface ParsedFunction {
  name: string
  subpath?: string
}

// Parse TypeScript source - extract all exported function/const names (raw, no sorting)
function parseSourceForNames(content: string, subpath?: string): ParsedFunction[] {
  const funcs: ParsedFunction[] = []
  const seen = new Set<string>()

  // Match exported functions with their preceding JSDoc (if any)
  // Only match implementations (with { or =), not overload signatures
  const regex = /(\/\*\*[\s\S]*?\*\/\s*)?export\s+(?:(?:async\s+)?function\s+(\w+)[^{]*\{|const\s+(\w+)\s*=)/g
  let match

  while ((match = regex.exec(content)) !== null) {
    const jsdoc = match[1] || ''
    const name = match[2] || match[3]

    // Skip if marked with @internal
    if (jsdoc.includes('@internal')) {
      continue
    }

    // Skip duplicates
    if (seen.has(name)) continue
    seen.add(name)

    funcs.push({ name, subpath })
  }

  return funcs
}

// Extract base name by removing prefixes and converting to display format
function getBaseName(name: string): string {
  // Remove prefixes only when followed by uppercase (e.g., setVolume -> Volume, but not setupPlayer)
  let base = name
  const startsWithOn = /^on[A-Z]/.test(name)
  base = base.replace(/^(get|set|use|on|handle|toggle|has|update)(?=[A-Z])/, '')

  // Only strip Changed/Updated/Received suffix for on* callbacks
  if (startsWithOn) {
    base = base.replace(/Changed$|Updated$|Received$/, '')
  }

  // Convert camelCase to Title Case with spaces
  return base.charAt(0).toUpperCase() + base.slice(1).replace(/([A-Z])/g, ' $1')
}

// Get the best function for a base name
// Priority: (no prefix, lowercase) > use > get > set > update > toggle > handle > on > has > (no prefix, uppercase)
function getBestFunction(baseName: string, allFuncs: ParsedFunction[]): ParsedFunction | undefined {
  const normalized = baseName.replace(/ /g, '').toLowerCase()

  // First: exact lowercase match (method with no prefix)
  const exactMatch = allFuncs.find(f => f.name.toLowerCase() === normalized && /^[a-z]/.test(f.name))
  if (exactMatch) return exactMatch

  // Then: prefixed lowercase matches (methods)
  for (const prefix of prefixOrder) {
    const candidate = prefix + normalized
    const match = allFuncs.find(f => f.name.toLowerCase() === candidate && /^[a-z]/.test(f.name))
    if (match) return match
  }

  // Last: uppercase matches (types)
  const typeMatch = allFuncs.find(f => f.name.toLowerCase() === normalized && /^[A-Z]/.test(f.name))
  if (typeMatch) return typeMatch

  // Fallback: find any name containing this base
  return allFuncs.find(f => f.name.toLowerCase().includes(normalized))
}

// Convert raw functions to deduplicated sidebar entries
function funcsToSidebarEntries(allFuncs: ParsedFunction[]): SidebarEntry[] {
  // Create deduplicated entries
  const entries: SidebarEntry[] = []
  const seen = new Set<string>()

  for (const func of allFuncs) {
    const base = getBaseName(func.name)
    if (seen.has(base)) continue
    seen.add(base)

    const bestFunc = getBestFunction(base, allFuncs)
    entries.push({
      name: base,
      anchor: bestFunc?.name.toLowerCase() || func.name.toLowerCase(),
      subpath: bestFunc?.subpath
    })
  }

  // Sort alphabetically by base name
  entries.sort((a, b) => a.name.localeCompare(b.name))

  return entries
}

function scanSourceFiles(srcDir: string): Map<string, SidebarEntry[]> {
  const modules = new Map<string, SidebarEntry[]>()
  const featuresDir = join(srcDir, 'features')

  for (const item of readdirSync(featuresDir)) {
    const itemPath = join(featuresDir, item)
    const stat = statSync(itemPath)

    if (stat.isDirectory()) {
      // Folder-based module (e.g., playback/, queue/)
      // Collect all functions from all .ts files (no subpaths - flattened into single page)
      const allFuncs: ParsedFunction[] = []
      for (const file of readdirSync(itemPath)) {
        if (!file.endsWith('.ts') || file === 'index.ts') continue
        const content = readFileSync(join(itemPath, file), 'utf-8')
        allFuncs.push(...parseSourceForNames(content))
      }
      if (allFuncs.length > 0) {
        modules.set(item, funcsToSidebarEntries(allFuncs))
      }
    } else if (item.endsWith('.ts') && item !== 'index.ts') {
      // Single-file module (no subpath needed)
      const content = readFileSync(itemPath, 'utf-8')
      const funcs = parseSourceForNames(content)
      if (funcs.length > 0) {
        modules.set(item.replace('.ts', ''), funcsToSidebarEntries(funcs))
      }
    }
  }

  return modules
}

function buildSidebar(modules: Map<string, SidebarEntry[]>): SidebarItem[] {
  const result: SidebarItem[] = []

  // Sort: priority modules first (in order), then rest alphabetically
  const sortedModules = [...modules.entries()].sort((a, b) => {
    const indexA = priorityModules.indexOf(a[0])
    const indexB = priorityModules.indexOf(b[0])
    if (indexA !== -1 && indexB !== -1) return indexA - indexB
    if (indexA !== -1) return -1
    if (indexB !== -1) return 1
    return a[0].localeCompare(b[0])
  })

  for (const [moduleName, entries] of sortedModules) {
    result.push({
      text: formatModuleName(moduleName),
      collapsed: collapsedByDefault.has(moduleName),
      items: entries.map(entry => {
        if (entry.name === '---') {
          return { text: '', link: '' } // separator
        }
        // Build link: /api/features/moduleName/#anchor
        const path = entry.subpath
          ? `/api/features/${moduleName}/${entry.subpath}/#${entry.anchor}`
          : `/api/features/${moduleName}/#${entry.anchor}`
        return { text: entry.name, link: path }
      }),
    })
  }

  return result
}

// Main - parse source files, include all functions except @nosidebar
const modules = scanSourceFiles('../src')
const sidebar = buildSidebar(modules)
writeFileSync('./api/typedoc-sidebar.json', JSON.stringify(sidebar, null, 2))
console.log(`Sidebar generated with ${modules.size} modules`)
