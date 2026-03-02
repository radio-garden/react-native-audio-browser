import type { Language } from '../../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from '../SourceFile.js';
import { FunctionType } from './FunctionType.js';
import type { GetCodeOptions, Type, TypeKind } from './Type.js';
export declare class PromiseType implements Type {
    readonly resultingType: Type;
    readonly errorType: Type;
    constructor(resultingType: Type);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    get resolverFunction(): FunctionType;
    get rejecterFunction(): FunctionType;
    getCode(language: Language, options?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
