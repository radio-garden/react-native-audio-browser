// Not upstream: a JS function that throws fails with a `jsi::JSError`, whose `what()` is
// `message\n\nstack`. Swift and Kotlin read only `what()`, so the error they get carries the message alone.
export function withoutJSStack(error) {
    return `
[](const std::exception_ptr& __error) -> std::exception_ptr {
  if (__error == nullptr) return __error;
  try {
    std::rethrow_exception(__error);
  } catch (const facebook::jsi::JSError& __jsError) {
    return std::make_exception_ptr(std::runtime_error(__jsError.getMessage()));
  } catch (...) {
    return __error;
  }
}(${error})`.trim();
}
