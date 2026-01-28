// Shared logic for base name extraction and prefix priority
// Used by both TypeDoc plugin and sidebar transform script

/**
 * Extract base name by removing common prefixes and suffixes
 */
export function getBaseName(name: string): string {
  let base = name

  // For event types ending in Event, strip Event suffix variants and module prefix
  if (name.endsWith('Event')) {
    base = base.replace(/(Changed|Updated|Ended)?Event$/, '')
    base = base.replace(
      /^(Playback|Navigation|Remote|Audio|Battery|Equalizer|Favorite|Metadata|Network|NowPlaying|Rating|Sleep)/,
      ''
    )
  }

  // For functions: strip common prefixes when followed by uppercase
  base = base.replace(prefixRegex, '')

  // Strip common suffixes (for on* callbacks)
  base = base.replace(/Changed$|Updated$|Received$|Ended$/, '')

  return base
}

/**
 * Prefix order for sorting and anchor selection.
 * Priority: (no prefix, lowercase) > use > get > set > update > toggle > handle > on > has > is > (no prefix, uppercase)
 */
export const prefixOrder = [
  'use',
  'get',
  'set',
  'update',
  'toggle',
  'handle',
  'on',
  'has',
  'is'
] as const

/** Regex to strip common prefixes when followed by uppercase */
export const prefixRegex = new RegExp(
  `^(${prefixOrder.join('|')})(?=[A-Z])`
)

/**
 * Get prefix priority index for sorting.
 */
export function getPrefixIndex(name: string): number {
  // Types (PascalCase starting with uppercase)
  if (/^[A-Z]/.test(name)) {
    return 100 // Types come last
  }
  // Check for known prefixes
  for (let i = 0; i < prefixOrder.length; i++) {
    if (name.toLowerCase().startsWith(prefixOrder[i])) {
      return i + 1 // +1 so plain methods (index 0) come first
    }
  }
  return 0 // Plain lowercase methods come first
}
