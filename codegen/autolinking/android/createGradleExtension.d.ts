import type { SourceFile } from '../../syntax/SourceFile.js';
export interface GradleFile extends Omit<SourceFile, 'language'> {
    language: 'gradle';
}
export declare function createGradleExtension(): GradleFile;
