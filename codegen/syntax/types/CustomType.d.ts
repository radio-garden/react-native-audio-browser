import type { CustomTypeConfig } from 'react-native-nitro-modules';
import type { Language } from '../../getPlatformSpecs.js';
import type { SourceFile, SourceImport } from '../SourceFile.js';
import type { Type, TypeKind } from './Type.js';
export declare class CustomType implements Type {
    typeConfig: CustomTypeConfig;
    typeName: string;
    constructor(typeName: string, typeConfig: CustomTypeConfig);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    getCode(language: Language): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
