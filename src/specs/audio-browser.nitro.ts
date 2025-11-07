import { type HybridObject } from 'react-native-nitro-modules';

import type { BrowserConfiguration, BrowserList, Track } from '../types';

export interface AudioBrowser
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  path: string | undefined
  tabs: Track[] | undefined

  // Browser navigation methods
  navigate(path: string): Promise<BrowserList>
  onSearch(query: string): Promise<Track[]>
  getContent(): BrowserList | undefined

  // Event callbacks
  onPathChanged: (path: string) => void
  onContentChanged: (content: BrowserList | undefined) => void
  onTabsChanged: (tabs: Track[]) => void
  configuration: BrowserConfiguration
}
