import type { Language } from '../../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, NamedType, Type, TypeKind } from './Type.js';
export interface GetFunctionCodeOptions extends GetCodeOptions {
    includeNameInfo?: boolean;
}
export declare class FunctionType implements Type {
    readonly returnType: Type;
    readonly parameters: NamedType[];
    constructor(returnType: Type, parameters: NamedType[], isSync?: boolean);
    get specializationName(): string;
    get jsName(): string;
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    /**
     * For a function, get the forward recreation of it:
     * If variable is called `func`, this would return:
     * ```cpp
     * [func = std::move(func)](Params... params) -> ReturnType {
     *   return func(params...);
     * }
     * ```
     */
    getForwardRecreationCode(variableName: string, language: Language): string;
    getCppFunctionPointerType(name: string, includeNameInfo?: boolean): string;
    getCode(language: Language, options?: GetFunctionCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
