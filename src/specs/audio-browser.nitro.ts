import { type HybridObject } from 'react-native-nitro-modules'

import type { NavigationError, NavigationErrorEvent } from '../features/errors'
import type { BrowserConfiguration, ResolvedTrack, Track } from '../types'

export interface AudioBrowser
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  path: string | undefined
  tabs: Track[] | undefined

  // Browser navigation methods
  navigatePath(path: string): void
  navigateTrack(track: Track): void
  onSearch(query: string): Promise<Track[]>
  getContent(): ResolvedTrack | undefined

  // Event callbacks
  onPathChanged: (path: string) => void
  onContentChanged: (content: ResolvedTrack | undefined) => void
  onTabsChanged: (tabs: Track[]) => void
  onNavigationError: (data: NavigationErrorEvent) => void

  // Error getter
  getNavigationError(): NavigationError | undefined

  configuration: BrowserConfiguration
}
