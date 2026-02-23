function isNotNullish<T>(val: T | undefined | null | void): val is T {
  return val !== undefined && val !== null
}

function assert(
  condition: any,
  message = 'assertion error'
): asserts condition {
  if (!condition) {
    throw new Error(message)
  }
}

export function assertNotNullish<T>(
  val: T | undefined | null | void,
  message = 'Expected value to not be nullish'
): asserts val is T {
  assert(isNotNullish(val), message)
}

export function assertedNotNullish<T>(
  val: T | undefined | null | void,
  message = 'Expected value to not be nullish'
): T {
  assertNotNullish(val, message)
  return val
}
