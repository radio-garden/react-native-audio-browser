import type { NitroUserConfig } from './NitroUserConfig.js';
/**
 * Represents the properly parsed `nitro.json` config of the current executing directory.
 */
export declare class NitroConfig {
    private readonly config;
    private static singleton;
    constructor(config: NitroUserConfig);
    static get current(): NitroConfig;
    /**
     * Returns the name of the Android C++ library (aka name in CMakeLists.txt `add_library(..)`).
     * This will be loaded via `System.loadLibrary(...)`.
     * @example `NitroTest`
     */
    getAndroidCxxLibName(): string;
    /**
     * Returns the iOS module name (aka Pod name) of the module that will be generated.
     * @example `NitroTest`
     */
    getIosModuleName(): string;
    /**
     * Represents the C++ namespace of the module that will be generated.
     * This can have multiple sub-namespaces, and is always relative to `margelo::nitro`.
     * @example `['image']` -> `margelo::nitro::image`
     */
    getCxxNamespace(language: 'c++' | 'swift', ...subDefinitionName: string[]): string;
    /**
     * Represents the Android namespace of the module that will be generated.
     * This can have multiple sub-namespaces, and is always relative to `com.margelo.nitro`.
     * @example `['image']` -> `com.margelo.nitro.image`
     */
    getAndroidPackage(language: 'java/kotlin' | 'c++/jni', ...subPackage: string[]): string;
    /**
     * Return the directory of the android package, and a given sub-package.
     * This will be used on android to put files from a package in their respective package folder.
     */
    getAndroidPackageDirectory(...subPackage: string[]): string[];
    /**
     * Get the autolinking configuration of all HybridObjects.
     * Those will be generated and default-constructed.
     */
    getAutolinkedHybridObjects(): NitroUserConfig['autolinking'];
    /**
     * Get the paths that will be ignored when loading the TypeScript project.
     * In most cases, this just contains `node_modules/`.
     */
    getIgnorePaths(): string[];
    getGitAttributesGeneratedFlag(): boolean;
    get isExternalConfig(): boolean;
    getSwiftBridgeHeaderName(): string;
    getSwiftBridgeNamespace(language: 'c++' | 'swift'): string;
}
