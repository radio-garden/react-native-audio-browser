import type { Language } from '../../getPlatformSpecs.js';
import { type FileWithReferencedTypes, type SourceFile, type SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, NamedType, Type, TypeKind } from './Type.js';
export declare class StructType implements Type {
    readonly structName: string;
    readonly properties: NamedType[];
    readonly declarationFile: FileWithReferencedTypes;
    constructor(structName: string, properties: NamedType[]);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    getCode(language: Language, { fullyQualified }?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
