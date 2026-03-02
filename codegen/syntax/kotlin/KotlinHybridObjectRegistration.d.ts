import type { SourceImport } from '../SourceFile.js';
interface Props {
    /**
     * The name of the Hybrid Object under which it should be registered and exposed to JS to.
     */
    hybridObjectName: string;
    /**
     * The name of the Kotlin/Java class that will be default-constructed
     */
    jniClassName: string;
}
interface JNIHybridObjectRegistration {
    cppCode: string;
    requiredImports: SourceImport[];
}
export declare function createJNIHybridObjectRegistration({ hybridObjectName, jniClassName, }: Props): JNIHybridObjectRegistration;
export {};
