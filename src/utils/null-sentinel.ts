/**
 * Sentinel type used to represent null values in Nitro variants.
 * This is necessary when we need to distinguish between undefined and null
 * in Nitro variants, where undefined means "not provided" and null means
 * "explicitly set to null".
 */
export type NullSentinel = { nullSentinel: true }

const nullSentinel: NullSentinel = { nullSentinel: true }

/**
 * Wraps a potentially null value for use in Nitro variants.
 *
 * This utility converts JavaScript null values into a NullSentinel type
 * that can be properly handled in Nitro variants. This is necessary when
 * we want to be able to distinguish between undefined and null - undefined
 * means the property wasn't provided, while null means it was explicitly
 * set to null.
 *
 * @param value - The value to wrap, can be null
 * @returns Either the original value or a NullSentinel if value was null
 *
 * @example
 * ```typescript
 * // JavaScript side - building options object:
 * const options = {
 *   optionalAndNullableValue: wrapNitroSentinel(value)
 * };
 *
 * // Where value could be:
 * // 1000 -> sends { optionalAndNullableValue: 1000 }
 * // null -> sends { optionalAndNullableValue: NullSentinel }
 * // undefined -> property becomes nil on the native side
 * ```
 *
 * ```kotlin
 * // Kotlin side:
 * options.optionalAndNullableValue?.let { variant ->
 *     value = when (variant) {
 *       is Variant_Double_NullSentinel.First -> variant.value  // 1000
 *       is Variant_Double_NullSentinel.Second -> null          // explicit null
 *     }
 * }
 * ```
 */
export function wrapNullSentinel<T>(value: T | null): T | NullSentinel {
  return value === null ? nullSentinel : value
}
