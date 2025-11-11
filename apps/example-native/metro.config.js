const path = require('path');
const { getDefaultConfig } = require('@react-native/metro-config');
const { withMetroConfig } = require('react-native-monorepo-config');

const root = path.resolve(__dirname, '..', '..');
// const commonAppPath = path.resolve(__dirname, '..', 'common-app');

/**
 * Metro configuration
 * https://facebook.github.io/metro/docs/configuration
 *
 * @type {import('metro-config').MetroConfig}
 */
const config = withMetroConfig(getDefaultConfig(__dirname), {
  root,
  dirname: __dirname,
});

// Add watchFolders to watch common-app
config.watchFolders = [
  root,
  // commonAppPath,
];

// Ensure node_modules are resolved correctly
config.resolver = {
  ...config.resolver,
  nodeModulesPaths: [
    path.resolve(__dirname, 'node_modules'),
    path.resolve(root, 'node_modules'),
  ],
};

module.exports = config;
