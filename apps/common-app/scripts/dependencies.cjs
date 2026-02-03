const path = require('path');

function resolveDependencies(dependencies, excludedDependencies, appDir) {
  const resolved = {};

  Object.keys(dependencies || {}).forEach((dep) => {
    if (!excludedDependencies.has(dep)) {
      // Resolve the dependency path relative to the app directory
      resolved[dep] = {
        root: path.resolve(appDir, 'node_modules', dep),
      };
    }
  });

  return resolved;
}

function getDependencies(appDir, exclude = []) {
  const commonAppDir = path.resolve(__dirname, '..');
  const commonAppPkg = require(path.resolve(commonAppDir, 'package.json'));

  const appPkg = require(path.resolve(appDir, 'package.json'));

  const excludedDependencies = new Set([
    ...Object.keys(appPkg.devDependencies || {}),
    ...Object.keys(appPkg.dependencies || {}),
    ...exclude,
  ]);

  return {
    // Get all common-app dependencies that aren't already in the current app
    ...resolveDependencies(
      commonAppPkg.devDependencies,
      excludedDependencies,
      appDir
    ),
    ...resolveDependencies(
      commonAppPkg.dependencies,
      excludedDependencies,
      appDir
    ),
  };
}

module.exports = {
  getDependencies,
};
