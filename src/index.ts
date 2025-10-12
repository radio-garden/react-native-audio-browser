import { NitroModules } from 'react-native-nitro-modules'
import type { ReactNativeAudioBrowser as ReactNativeAudioBrowserSpec } from './specs/react-native-audio-browser.nitro'

export const ReactNativeAudioBrowser =
  NitroModules.createHybridObject<ReactNativeAudioBrowserSpec>('ReactNativeAudioBrowser')