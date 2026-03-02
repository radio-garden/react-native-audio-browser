import type { Language } from '../../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, Type, TypeKind } from './Type.js';
export declare class TupleType implements Type {
    readonly itemTypes: Type[];
    constructor(itemTypes: Type[]);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    getCode(language: Language, options?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
