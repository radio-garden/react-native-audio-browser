import type { Language } from '../../getPlatformSpecs.js';
import type { SourceFile, SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, NamedType, Type, TypeKind } from './Type.js';
export declare class NamedWrappingType<T extends Type> implements NamedType {
    readonly type: T;
    readonly name: string;
    constructor(name: string, type: T);
    get escapedName(): string;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    get canBePassedByReference(): boolean;
    getCode(language: Language, options?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
