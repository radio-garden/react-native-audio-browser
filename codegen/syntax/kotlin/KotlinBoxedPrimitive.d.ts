import type { Type } from '../types/Type.js';
/**
 * Returns a boxed version of the given primitive type.
 * In JNI/Kotlin, primitive types (like `double` or `boolean`)
 * cannot be nullable, so we need to box them - e.g. as `JDouble` or `JBoolean`.
 */
export declare function getKotlinBoxedPrimitiveType(type: Type): string;
