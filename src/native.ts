import { NitroModules } from 'react-native-nitro-modules'
import type { AudioBrowser as AudioBrowserSpec } from './specs/audio-browser.nitro'
import type { AudioPlayer as AudioPlayerSpec } from './specs/audio-player.nitro'

export const nativePlayer =
  NitroModules.createHybridObject<AudioPlayerSpec>('AudioPlayer')

export const nativeBrowser =
  NitroModules.createHybridObject<AudioBrowserSpec>('AudioBrowser')
