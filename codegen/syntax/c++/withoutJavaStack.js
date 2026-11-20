// Not upstream: `jni::JniException::what()` is the Throwable's whole stack trace
// (`ExceptionHelper.getErrorDescription`), and JS reads only `what()`, so the error C++ gets carries
// the message alone, or the Throwable's `toString()` when it has none.
export function withoutJavaStack(throwable) {
    return `
[](jni::alias_ref<jni::JThrowable> __throwable) -> std::exception_ptr {
  try {
    auto __message = __throwable->getMessage();
    return std::make_exception_ptr(std::runtime_error(__message ? __message->toStdString() : __throwable->toString()));
  } catch (...) {
    return std::make_exception_ptr(jni::JniException(__throwable));
  }
}(${throwable})`.trim();
}
