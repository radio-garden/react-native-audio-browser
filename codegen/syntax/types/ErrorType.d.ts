import type { Language } from '../../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from '../SourceFile.js';
import type { Type, TypeKind } from './Type.js';
export declare class ErrorType implements Type {
    constructor();
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    getCode(language: Language): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
