import type { CodeNode } from './CodeNode.js';
import { type SourceFile, type SourceImport } from './SourceFile.js';
import type { Language } from '../getPlatformSpecs.js';
import type { Type } from './types/Type.js';
import { Method } from './Method.js';
export interface PropertyBody {
    getter: string;
    setter: string;
}
export type LanguageEnvironment = 'jvm' | 'swift' | 'other';
export interface PropertyModifiers {
    /**
     * The name of the class that defines this C++ property getter/setter method.
     * Example: `Person` -> `int Person::getAge()`
     */
    classDefinitionName?: string;
    /**
     * Whether the property should be marked as inlineable.
     */
    inline?: boolean;
    virtual?: boolean;
    /**
     * Whether the property is marked as `noexcept` (doesn't throw) or not.
     */
    noexcept?: boolean;
    /**
     * Whether this property overrides a base/super property.
     */
    override?: boolean;
    /**
     * Whether this property has a `@DoNotStrip` and `@Keep` attribute to avoid
     * it from being stripped from the binary by the Java compiler or ProGuard.
     */
    doNotStrip?: boolean;
}
export declare class Property implements CodeNode {
    readonly name: string;
    readonly type: Type;
    readonly isReadonly: boolean;
    constructor(name: string, type: Type, isReadonly: boolean);
    get jsSignature(): string;
    getExtraFiles(): SourceFile[];
    getRequiredImports(language: Language): SourceImport[];
    getGetterName(environment: LanguageEnvironment): string;
    getSetterName(environment: LanguageEnvironment): string;
    get cppGetter(): Method;
    get cppSetter(): Method | undefined;
    getCppMethods(): [getter: Method] | [getter: Method, setter: Method];
    getCode(language: Language, modifiers?: PropertyModifiers, body?: PropertyBody): string;
}
