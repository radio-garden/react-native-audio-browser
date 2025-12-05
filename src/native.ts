import { NitroModules } from 'react-native-nitro-modules'
import type { AudioBrowser as AudioBrowserSpec } from './specs/audio-browser.nitro.ts'

export const nativeBrowser =
  NitroModules.createHybridObject<AudioBrowserSpec>('AudioBrowser')
