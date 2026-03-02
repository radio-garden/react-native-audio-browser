import type { Language } from '../../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, Type, TypeKind } from './Type.js';
export declare class OptionalType implements Type {
    readonly wrappingType: Type;
    constructor(wrappingType: Type);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    get needsBraces(): boolean;
    getCode(language: Language, options?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
