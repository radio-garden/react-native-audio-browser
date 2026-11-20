// Not upstream: a JS function that throws fails with a `jsi::JSError`, whose `what()` is
// `message\n\nstack`. Swift and Kotlin read only `what()`, so the error they get carries the message alone.
export function withoutJSStack(error) {
    return `
[&]() -> std::exception_ptr {
  if (${error} == nullptr) return ${error};
  try {
    std::rethrow_exception(${error});
  } catch (const facebook::jsi::JSError& __jsError) {
    return std::make_exception_ptr(std::runtime_error(__jsError.getMessage()));
  } catch (...) {
    return ${error};
  }
}()`.trim();
}
