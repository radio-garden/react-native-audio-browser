import { type HybridObject } from 'react-native-nitro-modules';

import type { NavigationError, NavigationErrorEvent } from '../features/errors';
import type { NativeBrowserConfiguration } from '../types/browser-native';
import type { ResolvedTrack, Track } from '../types';

export interface AudioBrowser
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  path: string | undefined
  tabs: Track[] | undefined

  navigatePath(path: string): void
  navigateTrack(track: Track): void
  onSearch(query: string): Promise<Track[]>
  getContent(): ResolvedTrack | undefined

  onPathChanged: (path: string) => void
  onContentChanged: (content: ResolvedTrack | undefined) => void
  onTabsChanged: (tabs: Track[]) => void
  onNavigationError: (data: NavigationErrorEvent) => void

  getNavigationError(): NavigationError | undefined

  notifyContentChanged(path: string): void

  setFavorites(favorites: string[]): void

  configuration: NativeBrowserConfiguration
}
