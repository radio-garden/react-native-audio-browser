import type { CodeNode } from './CodeNode.js';
import type { Language } from '../getPlatformSpecs.js';
import { type SourceFile, type SourceImport } from './SourceFile.js';
import { Parameter } from './Parameter.js';
import type { Type } from './types/Type.js';
export type MethodBody = string;
export interface MethodModifiers {
    /**
     * The name of the class that defines this C++ method.
     * Example: `Person` -> `void Person::sayHi()`
     */
    classDefinitionName?: string;
    /**
     * Whether the function should be marked as inlineable.
     */
    inline?: boolean;
    virtual?: boolean;
    /**
     * Whether the function is marked as `noexcept` (doesn't throw) or not.
     */
    noexcept?: boolean;
    /**
     * Whether this function overrides a base/super function.
     */
    override?: boolean;
    /**
     * Whether this method has a `@DoNotStrip` and `@Keep` attribute to avoid
     * it from being stripped from the binary by the Java compiler or ProGuard.
     */
    doNotStrip?: boolean;
}
export declare class Method implements CodeNode {
    readonly name: string;
    readonly returnType: Type;
    readonly parameters: Parameter[];
    constructor(name: string, returnType: Type, parameters: Parameter[]);
    get jsSignature(): string;
    getCode(language: Language, modifiers?: MethodModifiers, body?: MethodBody): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
}
