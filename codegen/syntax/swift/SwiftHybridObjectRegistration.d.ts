import type { SourceImport } from '../SourceFile.js';
interface Props {
    /**
     * The name of the Hybrid Object under which it should be registered and exposed to JS to.
     */
    hybridObjectName: string;
    /**
     * The name of the Swift class that will be default-constructed
     */
    swiftClassName: string;
}
interface SwiftHybridObjectRegistration {
    cppCode: string;
    swiftRegistrationMethods: string;
    requiredImports: SourceImport[];
}
export declare function getAutolinkingNamespace(): string;
export declare function getHybridObjectConstructorCall(hybridObjectName: string): string;
export declare function getIsRecyclableCall(hybridObjectName: string): string;
export declare function createSwiftHybridObjectRegistration({ hybridObjectName, swiftClassName, }: Props): SwiftHybridObjectRegistration;
export {};
