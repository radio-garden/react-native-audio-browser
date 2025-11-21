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

  // Content notification
  /**
   * Notifies external controllers (Android Auto, CarPlay) that content at the given path has changed.
   * Controllers will refresh their UI if they're currently viewing this path.
   * Safe to call even if no controllers are subscribed (no-op).
   *
   * @param path - The path where content has changed (e.g., '/favorites', '/playlists/123')
   */
  notifyContentChanged(path: string): void

  configuration: BrowserConfiguration
}
