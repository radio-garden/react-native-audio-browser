import type { SourceFile } from '../SourceFile.js';
import type { EnumMember } from '../types/EnumType.js';
/**
 * Creates a C++ enum that converts to a JS enum (aka just int)
 */
export declare function createCppEnum(typename: string, enumMembers: EnumMember[]): SourceFile;
