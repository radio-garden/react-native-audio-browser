import { AppRegistry } from 'react-native'
import AudioBrowser from 'react-native-audio-browser'
import App from './App'
import { name as appName } from './app.json'
import { setupBrowser } from './src/utils/browser'

AppRegistry.registerComponent(appName, () => App)

void setupBrowser()

// For scripts/stream-cases, which drives the player through Metro's inspector.
if (__DEV__) globalThis.AudioBrowser = AudioBrowser
