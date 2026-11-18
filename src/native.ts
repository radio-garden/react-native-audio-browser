import { NitroModules } from 'react-native-nitro-modules'
import type { AudioBrowser as AudioBrowserSpec } from './specs/audio-browser.nitro.ts'

/**
 * The raw Nitro hybrid object — the bridge itself. Feature modules wrap it;
 * consumers use those wrappers.
 * @internal
 */
export const nativeBrowser =
  NitroModules.createHybridObject<AudioBrowserSpec>('AudioBrowser')
