import { FunctionType } from '../types/FunctionType.js';
import { StructType } from '../types/StructType.js';
export interface CyclicDependencyResult {
    /** Names of types that have cyclic dependencies with the source type */
    cyclicNames: Set<string>;
    /** Whether there are any cyclic dependencies */
    hasCyclicDeps: boolean;
}
/**
 * Detects cyclic dependencies between a struct and function types it references.
 *
 * When a struct contains a function type, and that function type references the struct
 * back (directly or indirectly), we have a cyclic dependency. This would cause circular
 * includes in the generated JNI headers.
 *
 * @param structType The struct to check for cyclic function dependencies
 * @returns Names of function types that have cyclic references back to this struct
 */
export declare function detectCyclicFunctionDependencies(structType: StructType): CyclicDependencyResult;
/**
 * Detects cyclic dependencies between a function and struct types it references.
 *
 * When a function contains a struct type parameter/return, and that struct contains
 * this function type, we have a cyclic dependency. This would cause circular
 * includes in the generated JNI headers.
 *
 * @param functionType The function to check for cyclic struct dependencies
 * @returns Names of struct types that have cyclic references back to this function
 */
export declare function detectCyclicStructDependencies(functionType: FunctionType): CyclicDependencyResult;
/**
 * Extracts the type name from a JNI header import name.
 * E.g., "JMyStruct.hpp" -> "MyStruct", "JFunc_void.hpp" -> "Func_void"
 *
 * Note: This relies on the consistent "J<TypeName>.hpp" naming convention.
 * A cleaner approach would be to store the original type name in the import
 * object itself, but that would require changes to the SourceImport interface
 * and all places that create imports.
 */
export declare function extractTypeNameFromImport(importName: string): string;
/**
 * Filters imports into regular and cyclic categories based on the cyclic names set.
 * Only JNI wrapper headers (J<TypeName>.hpp) are considered for cyclic filtering.
 * The shared C++ struct headers (<TypeName>.hpp) are always kept as regular imports.
 */
export declare function partitionImportsByCyclicDeps<T extends {
    name: string;
}>(imports: T[], cyclicNames: Set<string>): {
    regularImports: T[];
    cyclicImports: T[];
};
/**
 * Partitions imports into regular and cyclic include strings in a single pass.
 * Applies a transform function to each import and deduplicates the results.
 */
export declare function partitionAndTransformImports<T extends {
    name: string;
}>(imports: T[], cyclicNames: Set<string>, transform: (i: T) => string): {
    regularIncludes: string[];
    cyclicIncludes: string[];
};
