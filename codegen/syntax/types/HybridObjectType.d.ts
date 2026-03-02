import { type NitroConfig } from '../../config/NitroConfig.js';
import type { Language } from '../../getPlatformSpecs.js';
import type { HybridObjectSpec } from '../HybridObjectSpec.js';
import type { SourceFile, SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, Type, TypeKind } from './Type.js';
interface GetHybridObjectCodeOptions extends GetCodeOptions {
    mode?: 'strong' | 'weak';
}
export declare class HybridObjectType implements Type {
    readonly hybridObjectName: string;
    readonly implementationLanguage: Language;
    readonly baseTypes: HybridObjectType[];
    readonly sourceConfig: NitroConfig;
    constructor(hybridObjectName: string, implementationLanguage: Language, baseTypes: HybridObjectType[], sourceConfig: NitroConfig);
    constructor(spec: HybridObjectSpec);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    getCode(language: Language, options?: GetHybridObjectCodeOptions): string;
    getExtraFiles(): SourceFile[];
    private getExternalCxxImportName;
    getRequiredImports(language: Language): SourceImport[];
}
export {};
