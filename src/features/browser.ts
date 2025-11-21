import { nativeBrowser, nativePlayer } from '../native'
import type { BrowserConfiguration, ResolvedTrack, Track } from '../types'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

/**
 * Configures the browser with routes, tabs, and other settings.
 * Also registers the browser with the player to enable:
 * - Media URL transformation for authenticated playback
 * - Android Auto / CarPlay browsing integration
 * - Playback of browsable tracks via navigate()
 *
 * @param configuration - Browser configuration including routes, tabs, media config, etc.
 *
 * @example
 * ```ts
 * configureBrowser({
 *   routes: {
 *     '/albums/:id': { path: '/api/albums/:id' }
 *   },
 *   tabs: [
 *     { title: 'Home', url: '/' },
 *     { title: 'Search', url: '/search' }
 *   ]
 * })
 * ```
 */
export function configureBrowser(configuration: BrowserConfiguration): void {
  nativeBrowser.configuration = configuration
  nativePlayer.registerBrowser(nativeBrowser)
}

export function navigate(pathOrTrack: string | Track) {
  if (typeof pathOrTrack === 'string') {
    return nativeBrowser.navigatePath(pathOrTrack)
  } else {
    return nativeBrowser.navigateTrack(pathOrTrack)
  }
}

export function getPath() {
  return nativeBrowser.path
}

export const onPathChanged = NativeUpdatedValue.emitterize<string | undefined>(
  (cb) => (nativeBrowser.onPathChanged = cb)
)

export function usePath(): string | undefined {
  return useNativeUpdatedValue(getPath, onPathChanged)
}

export function getContent(): ResolvedTrack | undefined {
  return nativeBrowser.getContent()
}

export const onContentChanged = NativeUpdatedValue.emitterize<
  ResolvedTrack | undefined
>((cb) => (nativeBrowser.onContentChanged = cb))

export function useContent(): ResolvedTrack | undefined {
  return useNativeUpdatedValue(getContent, onContentChanged)
}
export function getTabs(): Track[] | undefined {
  return nativeBrowser.tabs
}

export const onTabsChanged = NativeUpdatedValue.emitterize<Track[] | undefined>(
  (cb) => (nativeBrowser.onTabsChanged = cb)
)

export function useTabs(): Track[] | undefined {
  return useNativeUpdatedValue(getTabs, onTabsChanged)
}

/**
 * Notifies external media controllers (Android Auto, CarPlay) that content
 * at the specified path has changed and should be refreshed.
 *
 * @param path - The path where content has changed (e.g., '/favorites')
 *
 * @example
 * ```ts
 * // After adding a track to favorites
 * notifyContentChanged('/favorites')
 * ```
 */
export const notifyContentChanged = (path: string): void => {
  nativeBrowser.notifyContentChanged(path)
}