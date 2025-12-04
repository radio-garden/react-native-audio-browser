import { Application, ReflectionKind, DeclarationReflection } from 'typedoc'
import { getBaseName, getPrefixIndex } from '../scripts/base-name.ts'

function sortByBaseName(children: DeclarationReflection[]) {
  children.sort((a, b) => {
    // Sort by base name
    const baseA = getBaseName(a.name).toLowerCase()
    const baseB = getBaseName(b.name).toLowerCase()

    if (baseA !== baseB) {
      return baseA.localeCompare(baseB)
    }

    // Same base name - sort by prefix priority
    return getPrefixIndex(a.name) - getPrefixIndex(b.name)
  })
}

export function load(app: Application) {
  // Run after all rendering preparation is done
  app.renderer.on('beginRender', (event) => {
    const project = event.project

    // Sort children of each module by base name and merge groups
    for (const reflection of project.getReflectionsByKind(ReflectionKind.Module)) {
      if (reflection.children) {
        sortByBaseName(reflection.children as DeclarationReflection[])

        // Merge all groups into a single group to maintain sort order
        if (reflection.groups && reflection.groups.length > 1) {
          const sortedChildren = [...reflection.children]
          reflection.groups = [{
            title: '',
            children: sortedChildren
          }]
        } else if (reflection.groups && reflection.groups.length === 1) {
          reflection.groups[0].children = [...reflection.children]
        }
      }
    }
  })
}
