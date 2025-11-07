import { nativeBrowser } from '../native'
import type {
  BrowserConfiguration,
  BrowserList,
  Track
} from '../types'
import { LazyEmitter } from '../utils/LazyEmitter'
import { useUpdatedNativeValue } from '../utils/useUpdatedNativeValue'

export function configureBrowser(configuration: BrowserConfiguration): void {
  nativeBrowser.configuration = configuration
}

export function navigate(path: string) {
  return (nativeBrowser.path = path)
}

export function getPath() {
  return nativeBrowser.path
}

export const onPathChanged = LazyEmitter.emitterize<string | undefined>(
  (cb) => (nativeBrowser.onPathChanged = cb)
)

export function usePath(): string | undefined {
  return useUpdatedNativeValue(getPath, onPathChanged)
}

export function getContent(): BrowserList | undefined {
  return nativeBrowser.getContent()
}

export const onContentChanged = LazyEmitter.emitterize<BrowserList | undefined>(
  (cb) => (nativeBrowser.onContentChanged = cb)
)

export function useContent(): BrowserList | undefined {
  return useUpdatedNativeValue(getContent, onContentChanged)
}
export function getTabs(): Track[] | undefined {
  return nativeBrowser.tabs
}

export const onTabsChanged = LazyEmitter.emitterize<Track[] | undefined>(
  (cb) => (nativeBrowser.onTabsChanged = cb)
)

export function useTabs(): Track[] | undefined {
  return useUpdatedNativeValue(getTabs, onTabsChanged)
}
