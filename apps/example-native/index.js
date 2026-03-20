import { AppRegistry } from 'react-native'
import App from './App'
import { name as appName } from './app.json'
import { setupBrowser } from './src/utils/browser'

AppRegistry.registerComponent(appName, () => App)

setupBrowser();
