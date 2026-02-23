const path = require('path')
const pkg = require('../../package.json')
const { getDependencies } = require('../common-app/scripts/dependencies.cjs')

const dependencies = getDependencies(__dirname)

/**
 * @type {import('@react-native-community/cli-types').Config}
 */
module.exports = {
  project: {
    ios: {
      automaticPodsInstallation: true
    }
  },
  dependencies: {
    [pkg.name]: {
      root: path.join(__dirname, '..', '..')
    },
    ...dependencies
  }
}
