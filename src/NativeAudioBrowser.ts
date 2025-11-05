import { NitroModules } from 'react-native-nitro-modules'
import type { AudioBrowser as AudioBrowserSpec } from './specs/audio-browser.nitro'

export const nativeAudioBrowser =
  NitroModules.createHybridObject<AudioBrowserSpec>('AudioBrowser')
