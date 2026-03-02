import { Type } from 'ts-morph';
import type { SourceFile } from './syntax/SourceFile.js';
import { type Language } from './getPlatformSpecs.js';
export declare function generatePlatformFiles(interfaceType: Type, language: Language): SourceFile[];
