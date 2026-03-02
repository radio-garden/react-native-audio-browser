import type { Language } from '../../getPlatformSpecs.js';
import type { BridgedType } from '../BridgedType.js';
import type { SourceFile, SourceImport } from '../SourceFile.js';
import type { Type } from '../types/Type.js';
export declare class KotlinCxxBridgedType implements BridgedType<'kotlin', 'c++'> {
    readonly type: Type;
    constructor(type: Type);
    get hasType(): boolean;
    get canBePassedByReference(): boolean;
    get needsSpecialHandling(): boolean;
    getRequiredImports(language: Language, visited?: Set<Type>): SourceImport[];
    getExtraFiles(visited?: Set<Type>): SourceFile[];
    asJniReferenceType(referenceType?: 'alias' | 'local' | 'global'): string;
    getTypeCode(language: 'kotlin' | 'c++', isBoxed?: boolean): string;
    parse(parameterName: string, from: 'c++' | 'kotlin', to: 'kotlin' | 'c++', inLanguage: 'kotlin' | 'c++'): string;
    dereferenceToJObject(parameterName: string): string;
    parseFromCppToKotlin(parameterName: string, language: 'kotlin' | 'c++', isBoxed?: boolean): string;
    parseFromKotlinToCpp(parameterName: string, language: 'kotlin' | 'c++', isBoxed?: boolean): string;
    private getFullJHybridObjectName;
}
