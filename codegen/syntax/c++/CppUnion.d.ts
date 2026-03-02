import type { SourceFile } from '../SourceFile.js';
import type { EnumMember } from '../types/EnumType.js';
/**
 * Creates a C++ enum that converts to a TypeScript union (aka just strings).
 */
export declare function createCppUnion(typename: string, enumMembers: EnumMember[]): SourceFile;
