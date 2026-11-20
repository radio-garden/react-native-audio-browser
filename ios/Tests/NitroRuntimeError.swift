/// Nitro's `RuntimeError` before 0.37.2: an `Error` whose message is only its `description`.
struct NitroRuntimeError: Error, CustomStringConvertible {
  let description: String
}
