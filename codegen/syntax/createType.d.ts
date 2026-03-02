import { Type as TSMorphType } from 'ts-morph';
import type { Type } from './types/Type.js';
import { NamedWrappingType } from './types/NamedWrappingType.js';
import { type Language } from '../getPlatformSpecs.js';
export declare function createNamedType(language: Language, name: string, type: TSMorphType, isOptional: boolean): NamedWrappingType<Type>;
export declare function createVoidType(): Type;
/**
 * Get a list of all currently known complex types.
 */
export declare function getAllKnownTypes(language?: Language): Type[];
export declare function addKnownType(key: string, type: Type, language: Language): void;
/**
 * Create a new type (or return it from cache if it is already known)
 */
export declare function createType(language: Language, type: TSMorphType, isOptional: boolean): Type;
