import type { BridgedType } from '../BridgedType.js';
import type { SourceFile, SourceImport } from '../SourceFile.js';
import type { Type } from '../types/Type.js';
import { type SwiftCxxHelper } from './SwiftCxxTypeHelper.js';
import type { Language } from '../../getPlatformSpecs.js';
export declare class SwiftCxxBridgedType implements BridgedType<'swift', 'c++'> {
    readonly type: Type;
    private readonly isBridgingToDirectCppTarget;
    constructor(type: Type, isBridgingToDirectCppTarget?: boolean);
    get hasType(): boolean;
    get canBePassedByReference(): boolean;
    get needsSpecialHandling(): boolean;
    getRequiredBridge(): SwiftCxxHelper | undefined;
    private getBridgeOrThrow;
    getRequiredImports(language: Language, visited?: Set<Type>): SourceImport[];
    getExtraFiles(visited?: Set<Type>): SourceFile[];
    getTypeCode(language: 'swift' | 'c++'): string;
    parse(parameterName: string, from: 'c++' | 'swift', to: 'swift' | 'c++', inLanguage: 'swift' | 'c++'): string;
    parseFromCppToSwift(cppParameterName: string, language: 'swift' | 'c++'): string;
    parseFromSwiftToCpp(swiftParameterName: string, language: 'swift' | 'c++'): string;
}
