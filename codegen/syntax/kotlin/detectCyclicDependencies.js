import { getReferencedTypes } from '../getReferencedTypes.js';
import { FunctionType } from '../types/FunctionType.js';
import { getTypeAs } from '../types/getTypeAs.js';
import { StructType } from '../types/StructType.js';
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
export function detectCyclicFunctionDependencies(structType) {
    const cyclicNames = new Set();
    const referencedFunctionTypes = getReferencedTypes(structType)
        .filter((t) => t.kind === 'function')
        .map((t) => getTypeAs(t, FunctionType));
    for (const funcType of referencedFunctionTypes) {
        if (doesTypeReferenceStruct(funcType, structType.structName)) {
            cyclicNames.add(funcType.specializationName);
        }
    }
    return {
        cyclicNames,
        hasCyclicDeps: cyclicNames.size > 0,
    };
}
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
export function detectCyclicStructDependencies(functionType) {
    const cyclicNames = new Set();
    const functionName = functionType.specializationName;
    const referencedStructTypes = getReferencedTypes(functionType)
        .filter((t) => t.kind === 'struct')
        .map((t) => getTypeAs(t, StructType));
    for (const structType of referencedStructTypes) {
        if (doesTypeReferenceFunction(structType, functionName)) {
            cyclicNames.add(structType.structName);
        }
    }
    return {
        cyclicNames,
        hasCyclicDeps: cyclicNames.size > 0,
    };
}
/**
 * Checks if a type references a struct with the given name
 */
function doesTypeReferenceStruct(type, structName) {
    const referencedTypes = getReferencedTypes(type);
    for (const refType of referencedTypes) {
        if (refType.kind === 'struct') {
            const refStruct = getTypeAs(refType, StructType);
            if (refStruct.structName === structName) {
                return true;
            }
        }
    }
    return false;
}
/**
 * Checks if a type references a function with the given specialization name
 */
function doesTypeReferenceFunction(type, functionName) {
    const referencedTypes = getReferencedTypes(type);
    for (const refType of referencedTypes) {
        if (refType.kind === 'function') {
            const refFunc = getTypeAs(refType, FunctionType);
            if (refFunc.specializationName === functionName) {
                return true;
            }
        }
    }
    return false;
}
/**
 * Checks if an import is a JNI wrapper header (starts with "J" and ends with ".hpp")
 * E.g., "JMyStruct.hpp" -> true, "MyStruct.hpp" -> false
 */
function isJniWrapperImport(importName) {
    return importName.startsWith('J') && importName.endsWith('.hpp');
}
/**
 * Extracts the type name from a JNI header import name.
 * E.g., "JMyStruct.hpp" -> "MyStruct", "JFunc_void.hpp" -> "Func_void"
 *
 * Note: This relies on the consistent "J<TypeName>.hpp" naming convention.
 * A cleaner approach would be to store the original type name in the import
 * object itself, but that would require changes to the SourceImport interface
 * and all places that create imports.
 */
export function extractTypeNameFromImport(importName) {
    return importName.replace(/^J/, '').replace(/\.hpp$/, '');
}
/**
 * Filters imports into regular and cyclic categories based on the cyclic names set.
 * Only JNI wrapper headers (J<TypeName>.hpp) are considered for cyclic filtering.
 * The shared C++ struct headers (<TypeName>.hpp) are always kept as regular imports.
 */
export function partitionImportsByCyclicDeps(imports, cyclicNames) {
    const regularImports = [];
    const cyclicImports = [];
    for (const i of imports) {
        // Only JNI wrappers (J*.hpp) can be cyclic
        const isCyclic = isJniWrapperImport(i.name) &&
            cyclicNames.has(extractTypeNameFromImport(i.name));
        if (isCyclic) {
            cyclicImports.push(i);
        }
        else {
            regularImports.push(i);
        }
    }
    return { regularImports, cyclicImports };
}
/**
 * Partitions imports into regular and cyclic include strings in a single pass.
 * Applies a transform function to each import and deduplicates the results.
 */
export function partitionAndTransformImports(imports, cyclicNames, transform) {
    const regularSet = new Set();
    const cyclicSet = new Set();
    for (const i of imports) {
        const isCyclic = isJniWrapperImport(i.name) &&
            cyclicNames.has(extractTypeNameFromImport(i.name));
        const header = transform(i);
        if (isCyclic) {
            cyclicSet.add(header);
        }
        else {
            regularSet.add(header);
        }
    }
    return {
        regularIncludes: Array.from(regularSet).sort(),
        cyclicIncludes: Array.from(cyclicSet).sort(),
    };
}
