import type { SourceImport } from '../SourceFile.js';
interface Props {
    /**
     * The name of the Hybrid Object under which it should be registered and exposed to JS to.
     */
    hybridObjectName: string;
    /**
     * The name of the C++ class that will be default-constructed
     */
    cppClassName: string;
}
interface CppHybridObjectRegistration {
    cppCode: string;
    requiredImports: SourceImport[];
}
export declare function createCppHybridObjectRegistration({ hybridObjectName, cppClassName, }: Props): CppHybridObjectRegistration;
export {};
