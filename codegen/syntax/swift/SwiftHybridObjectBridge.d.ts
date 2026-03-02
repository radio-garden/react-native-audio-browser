import type { HybridObjectSpec } from '../HybridObjectSpec.js';
import type { SourceFile } from '../SourceFile.js';
/**
 * Creates a Swift class that bridges Swift over to C++.
 * We need this because not all Swift types are accessible in C++, and vice versa.
 *
 * For example, Enums need to be converted to Int32 (because of a Swift compiler bug),
 * Promise<..> has to be converted to a Promise<..>, exceptions have to be handled
 * via custom Result types, etc..
 */
export declare function createSwiftHybridObjectCxxBridge(spec: HybridObjectSpec): SourceFile[];
