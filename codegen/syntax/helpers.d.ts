import type { SourceFile } from './SourceFile.js';
import type { Type } from './types/Type.js';
type Comment = '///' | '#';
export declare function createFileMetadataString(filename: string, comment?: Comment): string;
export declare function isFunction(type: Type): boolean;
export declare function toReferenceType(type: string): `const ${typeof type}&`;
export declare function escapeCppName(string: string): string;
/**
 * Compares the "looselyness" of the types.
 * Returns a positive number if {@linkcode a} is more loose than {@linkcode b},
 * and a negative number if otherwise.
 */
export declare function compareLooselyness(a: Type, b: Type): number;
export declare function isNotDuplicate<T>(item: T, index: number, array: T[]): boolean;
export declare function isCppFile(file: SourceFile): boolean;
export declare function getRelativeDirectory(file: SourceFile): string;
export declare function getRelativeDirectoryGenerated(...subpath: string[]): string;
export {};
