const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config')
const { getMonorepoMetroOptions } = require('../../scripts/metro.cjs')
const path = require('path')

const defaultConfig = getDefaultConfig(__dirname)

// Filter React and React Native to prevent duplicates
// Also filter react-native-safe-area-context to prevent context issues
// Also filter react-native-mmkv to prevent duplicate instances
const modulesToFilter = [
  'react',
  'react-native',
  'react-native-mmkv',
  'react-native-nitro-modules',
  'react-native-safe-area-context'
]
const { blockList, extraNodeModules } = getMonorepoMetroOptions(
  modulesToFilter,
  __dirname,
  defaultConfig
)

const monorepoRoot = path.resolve(__dirname, '..', '..')
const appsRoot = path.resolve(monorepoRoot, 'apps')

/**
 * Metro configuration for monorepo
 * https://facebook.github.io/metro/docs/configuration
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  projectRoot: __dirname,
  watchFolders: [monorepoRoot, appsRoot],

  resolver: {
    blockList,
    extraNodeModules
  },

  transformer: {
    getTransformOptions: async () => ({
      transform: {
        experimentalImportSupport: false,
        inlineRequires: true
      }
    })
  }
}

module.exports = mergeConfig(defaultConfig, config)
