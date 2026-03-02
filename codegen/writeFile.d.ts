import type { SourceFile } from './syntax/SourceFile.js';
/**
 * Writes the given file to disk and returns its actual path.
 */
export declare function writeFile(basePath: string, file: SourceFile): Promise<string>;
