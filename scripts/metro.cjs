const path = require('path');

function getModuleBlocklist(modulesToFilter, defaultConfig, appDir) {
  const blocklistRE = defaultConfig?.resolver?.blockList;
  const defaultBlocklist = Array.isArray(blocklistRE)
    ? blocklistRE
    : blocklistRE
      ? [blocklistRE]
      : [];

  // Get absolute paths to directories to block
  const monorepoRoot = path.resolve(appDir, '..', '..');
  const commonAppPath = path.resolve(monorepoRoot, 'apps', 'common-app');
  const libraryPath = monorepoRoot; // Block from root node_modules

  const blockedDirs = [monorepoRoot, commonAppPath, libraryPath];

  // Block specified modules from being resolved in blocked directories
  const modulePatterns = modulesToFilter.flatMap((moduleName) =>
    blockedDirs.map((dir) =>
      new RegExp(
        `^${path.join(dir, 'node_modules', moduleName).replace(/\\/g, '/')}/.*$`
      )
    )
  );

  return [...defaultBlocklist, ...modulePatterns];
}

function getExtraNodeModules(modulesToFilter, appDir) {
  const extraNodeModules = {};

  modulesToFilter.forEach((moduleName) => {
    extraNodeModules[moduleName] = path.resolve(
      appDir,
      'node_modules',
      moduleName
    );
  });

  return extraNodeModules;
}

function getMonorepoMetroOptions(modulesToFilter, appDir, defaultConfig) {
  const blockList = getModuleBlocklist(modulesToFilter, defaultConfig, appDir);
  const extraNodeModules = getExtraNodeModules(modulesToFilter, appDir);

  return {
    blockList,
    extraNodeModules,
  };
}

module.exports = {
  getMonorepoMetroOptions,
};
