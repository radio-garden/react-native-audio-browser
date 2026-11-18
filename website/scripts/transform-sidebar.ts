import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs'
import { join } from 'path'
import { prefixOrder, prefixRegex } from './base-name.ts'

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

// Display-name overrides for module groups; everything else is the
// camelCase-spaced module name.
const moduleDisplayNames: Record<string, string> = {
  carConnection: 'Car'
}

function formatModuleName(str: string): string {
  const override = moduleDisplayNames[str]
  if (override) return override
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
  'remoteControls'
])

// Priority modules shown first (in this order), rest alphabetical
const priorityModules = [
  'player',
  'browser',
  'playback',
  'errors',
  'queue',
  'nowPlaying'
]

interface ParsedFunction {
  name: string
  subpath?: string
}

// Parse TypeScript source - extract all exported function/const names (raw, no sorting)
function parseSourceForNames(
  content: string,
  subpath?: string
): ParsedFunction[] {
  const funcs: ParsedFunction[] = []
  const seen = new Set<string>()

  // Match exported functions with their preceding JSDoc (if any)
  // Only match implementations (with { or =), not overload signatures
  // The JSDoc group is tempered ((?!\*\/)) so it can't span across an earlier
  // comment's close and swallow another declaration's @internal.
  const regex =
    /(\/\*\*(?:(?!\*\/)[\s\S])*\*\/\s*)?export\s+(?:(?:async\s+)?function\s+(\w+)[^{]*\{|const\s+(\w+)\s*=)/g
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
  base = base.replace(prefixRegex, '')

  // Only strip Changed/Updated/Received suffix for on* callbacks
  if (startsWithOn) {
    base = base.replace(/Changed$|Updated$|Received$/, '')
  }

  // Convert camelCase to Title Case with spaces
  return base.charAt(0).toUpperCase() + base.slice(1).replace(/([A-Z])/g, ' $1')
}

// Get the best function for a base name (see prefixOrder in base-name.ts)
function getBestFunction(
  baseName: string,
  allFuncs: ParsedFunction[]
): ParsedFunction | undefined {
  const normalized = baseName.replace(/ /g, '').toLowerCase()

  // First: exact lowercase match (method with no prefix)
  const exactMatch = allFuncs.find(
    (f) => f.name.toLowerCase() === normalized && /^[a-z]/.test(f.name)
  )
  if (exactMatch) return exactMatch

  // Then: prefixed lowercase matches (methods). Event names had their
  // Changed/Updated/… suffix stripped from the base, so reconstruct those
  // variants at the 'on' position — otherwise onFavoriteChanged is
  // unreachable and the substring fallback picks an unrelated function.
  for (const prefix of prefixOrder) {
    const candidates =
      prefix === 'on'
        ? [
            prefix + normalized,
            ...['changed', 'updated', 'received', 'ended'].map(
              (suffix) => prefix + normalized + suffix
            )
          ]
        : [prefix + normalized]
    for (const candidate of candidates) {
      const match = allFuncs.find(
        (f) => f.name.toLowerCase() === candidate && /^[a-z]/.test(f.name)
      )
      if (match) return match
    }
  }

  // Last: uppercase matches (types)
  const typeMatch = allFuncs.find(
    (f) => f.name.toLowerCase() === normalized && /^[A-Z]/.test(f.name)
  )
  if (typeMatch) return typeMatch

  // Fallback: find any name containing this base
  return allFuncs.find((f) => f.name.toLowerCase().includes(normalized))
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
    // A base whose only function is an on* event keeps its suffix in the
    // label ("Favorite Changed", not a bare "Favorite" that reads like an
    // action and collides with sibling entries). Likewise a base whose only
    // function is a clear* action keeps its verb ("Clear Now Playing Flash",
    // not "Now Playing Flash" beside "Flash Now Playing").
    let name = base
    if (bestFunc && /^on[A-Z]/.test(bestFunc.name)) {
      name = bestFunc.name
        .slice(2)
        .replace(/([A-Z])/g, ' $1')
        .trim()
    } else if (bestFunc && /^clear[A-Z]/.test(bestFunc.name)) {
      name = 'Clear ' + base
    }
    entries.push({
      name,
      anchor: bestFunc?.name.toLowerCase() || func.name.toLowerCase(),
      subpath: bestFunc?.subpath
    })
  }

  return entries
}

// Feature-module types (PlaybackErrorEvent, RetryConfig, …) join their group
// verbatim, like the Types section. A type whose name collides with a
// function entry's base ("PlaybackError" vs the "Playback Error" hook entry)
// is skipped — its section sits adjacent on the same page.
function typesToSidebarEntries(
  typeNames: string[],
  existing: SidebarEntry[]
): SidebarEntry[] {
  const taken = new Set(
    existing.map((e) => e.name.replace(/ /g, '').toLowerCase())
  )
  const entries: SidebarEntry[] = []
  for (const name of typeNames) {
    if (taken.has(name.toLowerCase())) continue
    taken.add(name.toLowerCase())
    entries.push({ name, anchor: name.toLowerCase() })
  }
  return entries
}

interface ModuleEntries {
  funcs: SidebarEntry[]
  types: SidebarEntry[]
}

function moduleEntries(
  allFuncs: ParsedFunction[],
  typeNames: string[]
): ModuleEntries {
  const funcs = funcsToSidebarEntries(allFuncs)
  const types = typesToSidebarEntries(typeNames, funcs)
  funcs.sort((a, b) => a.name.localeCompare(b.name))
  types.sort((a, b) => a.name.localeCompare(b.name))
  return { funcs, types }
}

// Files in features/ that are internal helpers, not public API. They are not
// re-exported from features/index.ts and are excluded from the generated docs
// pages too (see `exclude` in typedoc.json) — keep the two lists in sync.
const internalFeatureFiles = new Set(['browser-config.ts'])

// Type / utility entry points documented under /api/types/<base>/ and
// /api/utils/<base>/ — keep in sync with `entryPoints` in typedoc.json.
const typeFiles = ['browser-nodes.ts', 'browser.ts']
const utilFiles = [
  'getTrackIdentity.ts',
  'useDebug.ts',
  'NativeUpdatedValue.ts',
  'LazyNativeEmitter.ts'
]

// Exported type/interface/class names, shown verbatim (they're what a reader
// searches for), skipping @internal.
function parseSourceForTypeNames(content: string): string[] {
  const names: string[] = []
  const regex =
    /(\/\*\*(?:(?!\*\/)[\s\S])*\*\/\s*)?export\s+(?:interface|type|class|enum)\s+(\w+)/g
  let match
  while ((match = regex.exec(content)) !== null) {
    const jsdoc = match[1] || ''
    if (jsdoc.includes('@internal')) continue
    names.push(match[2])
  }
  return names
}

// One flat, alphabetical group per section: every exported name linking to its
// anchor on the module's page.
function buildReferenceGroup(
  text: string,
  dir: string,
  files: string[],
  linkRoot: string
): SidebarItem {
  const items: SidebarItem[] = []
  for (const file of files) {
    const base = file.replace('.ts', '')
    const content = readFileSync(join(dir, file), 'utf-8')
    for (const name of [
      ...parseSourceForTypeNames(content),
      ...parseSourceForNames(content).map((f) => f.name)
    ]) {
      items.push({
        text: name,
        link: `${linkRoot}/${base}/#${name.toLowerCase()}`
      })
    }
  }
  items.sort((a, b) => a.text.localeCompare(b.text))
  return { text, collapsed: true, items }
}

function scanSourceFiles(srcDir: string): Map<string, SidebarEntry[]> {
  const modules = new Map<string, SidebarEntry[]>()
  const featuresDir = join(srcDir, 'features')

  for (const item of readdirSync(featuresDir)) {
    if (internalFeatureFiles.has(item)) continue
    const itemPath = join(featuresDir, item)
    const stat = statSync(itemPath)

    if (stat.isDirectory()) {
      // Folder-based module (e.g., playback/, queue/)
      // Collect all functions from all .ts files (no subpaths - flattened into single page)
      const allFuncs: ParsedFunction[] = []
      const typeNames: string[] = []
      for (const file of readdirSync(itemPath)) {
        if (!file.endsWith('.ts') || file === 'index.ts') continue
        const content = readFileSync(join(itemPath, file), 'utf-8')
        allFuncs.push(...parseSourceForNames(content))
        typeNames.push(...parseSourceForTypeNames(content))
      }
      if (allFuncs.length > 0) {
        modules.set(item, moduleEntries(allFuncs, typeNames))
      }
    } else if (item.endsWith('.ts') && item !== 'index.ts') {
      // Single-file module (no subpath needed)
      const content = readFileSync(itemPath, 'utf-8')
      const funcs = parseSourceForNames(content)
      if (funcs.length > 0) {
        modules.set(
          item.replace('.ts', ''),
          moduleEntries(funcs, parseSourceForTypeNames(content))
        )
      }
    }
  }

  return modules
}

function buildSidebar(modules: Map<string, ModuleEntries>): SidebarItem[] {
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
    const toItem = (entry: SidebarEntry): SidebarItem => {
      if (entry.name === '---') {
        return { text: '', link: '' } // separator
      }
      // Build link: /api/features/moduleName/#anchor
      const path = entry.subpath
        ? `/api/features/${moduleName}/${entry.subpath}/#${entry.anchor}`
        : `/api/features/${moduleName}/#${entry.anchor}`
      return { text: entry.name, link: path }
    }
    const items = entries.funcs.map(toItem)
    // The module's types live in a collapsed sub-group so they're reachable
    // without crowding the function entries.
    if (entries.types.length > 0) {
      items.push({
        text: `${formatModuleName(moduleName)} Types`,
        collapsed: true,
        items: entries.types.map(toItem)
      })
    }
    result.push({
      text: formatModuleName(moduleName),
      collapsed: collapsedByDefault.has(moduleName),
      items
    })
  }

  return result
}

// Main - parse source files, include all functions except @nosidebar
const modules = scanSourceFiles('../src')
const sidebar = buildSidebar(modules)
sidebar.push(
  buildReferenceGroup('Types', '../src/types', typeFiles, '/api/types'),
  buildReferenceGroup('Utils', '../src/utils', utilFiles, '/api/utils')
)
writeFileSync('./api/typedoc-sidebar.json', JSON.stringify(sidebar, null, 2))
console.log(`Sidebar generated with ${modules.size} modules`)
