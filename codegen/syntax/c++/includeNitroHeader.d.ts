import type { SourceImport } from '../SourceFile.js';
/**
 * Generates C++ code for including a `NitroModules` header.
 * @example `Hash.hpp` -> `#include <NitroModules/Hash.hpp>`
 */
export declare function includeNitroHeader(headerName: string): string;
export declare function includeHeader(sourceImport: SourceImport, force?: boolean): string;
