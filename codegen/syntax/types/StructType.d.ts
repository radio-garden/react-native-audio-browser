import type { Language } from '../../getPlatformSpecs.js';
import { type FileWithReferencedTypes, type SourceFile, type SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, NamedType, Type, TypeKind } from './Type.js';
export declare class StructType implements Type {
    readonly structName: string;
    private _propertiesInput;
    private _properties;
    private _declarationFile;
    private _isInitializing;
    constructor(structName: string, properties: NamedType[] | (() => NamedType[]));
    private ensureInitialized;
    get initialized(): boolean;
    get properties(): NamedType[];
    get declarationFile(): FileWithReferencedTypes;
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    getCode(language: Language, { fullyQualified }?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
