import type { Language } from '../getPlatformSpecs.js';
import type { HybridObjectSpec } from './HybridObjectSpec.js';
/**
 * Returns true when the given {@linkcode memberName} is overriding a
 * property or method from any base class inside the given
 * {@linkcode hybridObject}'s prototype chain (all the way up).
 *
 * For example, `"toString"` would return `true` since it overrides from base HybridObject.
 * On Kotlin, `"hashCode"` would return `true` since it overrides from base `kotlin.Any`.
 */
export declare function isMemberOverridingFromBase(memberName: string, hybridObject: HybridObjectSpec, language: Language): boolean;
