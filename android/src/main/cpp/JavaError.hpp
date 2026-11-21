#pragma once

#include <fbjni/fbjni.h>
#include <string>
#include <string_view>
#include <utility>

namespace margelo::nitro::audiobrowser {

/**
 * A Kotlin error crossing to C++, which the generated bridge rejects or throws with (codegen's
 * withoutJavaStack). fbjni's JniException makes `what()` the Java class and stack, which JS reads
 * as the error's message; here it is the Throwable's message, or its class when that is empty.
 * Crossing back to Java, it is still the original Throwable: fbjni catches JniException.
 */
class JavaError final : public facebook::jni::JniException {
public:
  explicit JavaError(const facebook::jni::alias_ref<facebook::jni::JThrowable>& throwable)
      : JniException(throwable), _message(messageOf(getThrowable())) {}

  const char* what() const noexcept override {
    return _message.c_str();
  }

private:
  std::string _message;

  // Never throws, so the error still reaches JS when reading its message fails.
  static std::string messageOf(facebook::jni::local_ref<facebook::jni::JThrowable> throwable) noexcept {
    try {
      auto message = throwable->getMessage();
      std::string text = message ? message->toStdString() : std::string();
      if (!text.empty()) return text;
      // `toString()` is `Class: message`, or `Class: ` when the message is empty.
      std::string description = throwable->toString();
      constexpr std::string_view emptyMessage = ": ";
      if (description.ends_with(emptyMessage)) description.resize(description.size() - emptyMessage.size());
      return description;
    } catch (...) {
      return "Unknown Java exception";
    }
  }
};

[[noreturn]] [[gnu::noinline]] inline void throwJavaError(const facebook::jni::JniException& error) {
  throw JavaError(error.getThrowable());
}

/**
 * Calls a Kotlin method or function through fbjni, rethrowing what it throws as JavaError. Its
 * arguments are converted before the call, and its result after it, so a failure there keeps
 * fbjni's message.
 */
template <typename Method, typename... Args>
decltype(auto) callJava(const Method& method, Args&&... args) {
  try {
    return method(std::forward<Args>(args)...);
  } catch (const JavaError&) {
    throw;
  } catch (const facebook::jni::JniException& error) {
    throwJavaError(error);
  }
}

} // namespace margelo::nitro::audiobrowser
