import type { Language } from '../../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, Type, TypeKind } from './Type.js';
export declare class RecordType implements Type {
    readonly keyType: Type;
    readonly valueType: Type;
    constructor(keyType: Type, valueType: Type);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    getCode(language: Language, options?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
