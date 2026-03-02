import type { PlatformSpec } from 'react-native-nitro-modules';
import type { InterfaceDeclaration, Type, TypeAliasDeclaration } from 'ts-morph';
export type Platform = keyof Required<PlatformSpec>;
export type Language = Required<PlatformSpec>[keyof PlatformSpec];
export declare function isDirectlyHybridObject(type: Type): boolean;
export declare function isDirectlyAnyHybridObject(type: Type): boolean;
export declare function extendsHybridObject(type: Type, recursive: boolean): boolean;
export declare function isHybridViewProps(type: Type): boolean;
export declare function isHybridViewMethods(type: Type): boolean;
export declare function isHybridView(type: Type): boolean;
export declare function isAnyHybridSubclass(type: Type): boolean;
/**
 * If the given interface ({@linkcode declaration}) extends `HybridObject`,
 * this method returns the platforms it exists on.
 * If it doesn't extend `HybridObject`, this returns `undefined`.
 */
export declare function getHybridObjectPlatforms(declaration: InterfaceDeclaration | TypeAliasDeclaration): PlatformSpec;
export declare function getHybridViewPlatforms(view: InterfaceDeclaration | TypeAliasDeclaration): PlatformSpec;
