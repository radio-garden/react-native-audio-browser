import type { Language } from '../../getPlatformSpecs.js';
import type { SourceFile, SourceImport } from '../SourceFile.js';
import type { Type, TypeKind } from './Type.js';
export declare class BigIntType implements Type {
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    getCode(language: Language): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(): SourceImport[];
}
