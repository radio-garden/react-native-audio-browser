import type { SourceImport } from '../SourceFile.js';
import type { Type } from '../types/Type.js';
export interface SwiftCxxHelper {
    cxxHeader: {
        code: string;
        requiredIncludes: SourceImport[];
    };
    cxxImplementation?: {
        code: string;
        requiredIncludes: SourceImport[];
    };
    funcName: string;
    specializationName: string;
    cxxType: string;
    dependencies: SwiftCxxHelper[];
}
export declare function createSwiftCxxHelpers(type: Type): SwiftCxxHelper | undefined;
