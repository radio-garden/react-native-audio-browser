import type { SourceFile } from '../../syntax/SourceFile.js';
export interface RubyFile extends Omit<SourceFile, 'language'> {
    language: 'ruby';
}
export declare function createPodspecRubyExtension(): RubyFile;
