import type { SourceFile } from '../../syntax/SourceFile.js';
export interface CMakeFile extends Omit<SourceFile, 'language'> {
    language: 'cmake';
}
export declare function getBuildingWithGeneratedCmakeDefinition(): string;
export declare function createCMakeExtension(files: SourceFile[]): CMakeFile;
