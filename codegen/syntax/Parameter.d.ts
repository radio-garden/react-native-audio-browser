import type { ParameterDeclaration } from 'ts-morph';
import type { CodeNode } from './CodeNode.js';
import type { Language } from '../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from './SourceFile.js';
import type { NamedType, Type } from './types/Type.js';
export declare class Parameter implements CodeNode {
    readonly type: NamedType;
    constructor(name: string, type: Type);
    constructor(parameter: ParameterDeclaration, language: Language);
    get jsSignature(): string;
    get name(): string;
    getCode(language: Language): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
