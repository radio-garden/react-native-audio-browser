import type { ts, Type } from 'ts-morph';
import type { NamedType } from './types/Type.js';
import type { Language } from '../getPlatformSpecs.js';
export declare function getInterfaceProperties(language: Language, interfaceType: Type<ts.ObjectType>): NamedType[];
