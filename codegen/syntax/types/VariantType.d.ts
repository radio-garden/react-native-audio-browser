import type { Language } from '../../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from '../SourceFile.js';
import type { GetCodeOptions, Type, TypeKind } from './Type.js';
export declare const VariantLabels: readonly ["first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eigth", "ninth", "tenth"];
type VariantLabel = (typeof VariantLabels)[number];
export declare class VariantType implements Type {
    readonly variants: Type[];
    readonly aliasName?: string;
    constructor(variants: Type[], aliasName?: string);
    get canBePassedByReference(): boolean;
    get kind(): TypeKind;
    get isEquatable(): boolean;
    get jsType(): string;
    get cases(): [VariantLabel, Type][];
    getAliasName(language: Language, options?: GetCodeOptions): string;
    getCode(language: Language, options?: GetCodeOptions): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
export {};
