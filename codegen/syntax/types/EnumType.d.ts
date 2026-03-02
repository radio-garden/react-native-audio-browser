import { EnumDeclaration } from 'ts-morph';
import { Type as TSMorphType, type ts } from 'ts-morph';
import type { Language } from '../../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, Type, TypeKind } from './Type.js';
export interface EnumMember {
    name: string;
    value: number;
    stringValue: string;
}
export declare class EnumType implements Type {
    readonly enumName: string;
    readonly enumMembers: EnumMember[];
    readonly jsType: 'enum' | 'union';
    readonly declarationFile: SourceFile;
    constructor(enumName: string, enumDeclaration: EnumDeclaration);
    constructor(enumName: string, union: TSMorphType<ts.UnionType>);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    getCode(language: Language, { fullyQualified }?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
