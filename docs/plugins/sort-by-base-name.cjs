// @ts-check
const { Application, ReflectionKind } = require('typedoc')

/**
 * Extract base name by removing common prefixes and suffixes
 * @param {string} name
 * @returns {string}
 */
function getBaseName(name) {
  let base = name

  // For event types ending in Event, strip Event suffix variants and module prefix
  // PlaybackPlayWhenReadyChangedEvent -> PlayWhenReady
  // PlaybackProgressUpdatedEvent -> Progress
  // RepeatModeChangedEvent -> RepeatMode
  if (name.endsWith('Event')) {
    base = base.replace(/(Changed|Updated|Ended)?Event$/, '')
    // Strip common module prefixes (Playback, Navigation, Remote, etc.)
    base = base.replace(/^(Playback|Navigation|Remote|Audio|Battery|Equalizer|Favorite|Metadata|Network|NowPlaying|Rating|Sleep)/, '')
  }

  // For functions: strip common prefixes when followed by uppercase
  base = base.replace(/^(get|set|use|on|handle|toggle|has|update)(?=[A-Z])/, '')

  // Strip common suffixes (for on* callbacks)
  base = base.replace(/Changed$|Updated$|Received$|Ended$/, '')

  return base
}

/**
 * Sort reflections by base name
 * @param {import('typedoc').DeclarationReflection[]} children
 */
function sortByBaseName(children) {
  children.sort((a, b) => {
    // Sort by base name
    const baseA = getBaseName(a.name).toLowerCase()
    const baseB = getBaseName(b.name).toLowerCase()

    if (baseA !== baseB) {
      return baseA.localeCompare(baseB)
    }

    // Same base name - sort by prefix priority: use, get, set, update, toggle, handle, on, has, other, types last
    const getPrefixIndex = (name) => {
      // Types (PascalCase starting with uppercase, ending with Event or no prefix match)
      if (/^[A-Z]/.test(name) && (name.endsWith('Event') || name.endsWith('State') || !name.match(/^(get|set|use|on|handle|toggle|has|update)/i))) {
        return 100 // Types come last
      }
      const prefixOrder = ['use', 'get', 'set', 'update', 'toggle', 'handle', 'on', 'has']
      for (let i = 0; i < prefixOrder.length; i++) {
        if (name.toLowerCase().startsWith(prefixOrder[i])) {
          return i
        }
      }
      return 50 // Other functions before types
    }

    return getPrefixIndex(a.name) - getPrefixIndex(b.name)
  })
}

/**
 * @param {import('typedoc').Application} app
 */
function load(app) {
  // Run after all rendering preparation is done
  app.renderer.on('beginRender', (event) => {
    const project = event.project

    // Sort children of each module by base name and merge groups
    for (const reflection of project.getReflectionsByKind(ReflectionKind.Module)) {
      if (reflection.children) {
        sortByBaseName(reflection.children)

        // Merge all groups into a single group to maintain sort order
        if (reflection.groups && reflection.groups.length > 1) {
          // Collect all children from all groups in sorted order
          const sortedChildren = [...reflection.children]
          // Create single group with all children
          reflection.groups = [{
            title: '',
            children: sortedChildren
          }]
        } else if (reflection.groups && reflection.groups.length === 1) {
          // Single group - just update its children with sorted order
          reflection.groups[0].children = [...reflection.children]
        }
      }
    }
  })
}

module.exports = { load }
