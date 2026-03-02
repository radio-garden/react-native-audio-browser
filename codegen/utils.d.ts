import type { SourceFile } from './syntax/SourceFile.js';
import type { SwiftCxxHelper } from './syntax/swift/SwiftCxxTypeHelper.js';
import type { Type } from 'ts-morph';
import { NitroConfig } from './config/NitroConfig.js';
export declare function capitalizeName(name: string): string;
export declare function createIndentation(spacesCount: number): string;
export declare function indent(string: string, spacesCount: number): string;
export declare function indent(string: string, indentation: string): string;
export declare function errorToString(error: unknown): string;
export declare function escapeComments(string: string): string;
export declare function toUnixPath(p: string): string;
export declare function unsafeFastJoin(...segments: string[]): string;
/**
 * Deduplicates all files via their full path.
 * If content differs, you are f*cked.
 */
export declare function deduplicateFiles(files: SourceFile[]): SourceFile[];
export declare function filterDuplicateHelperBridges(bridge: SwiftCxxHelper, i: number, array: SwiftCxxHelper[]): boolean;
export declare function toLowerCamelCase(string: string): string;
export declare function getBaseTypes(type: Type): Type[];
export declare function getHybridObjectNitroModuleConfig(type: Type): NitroConfig | undefined;
