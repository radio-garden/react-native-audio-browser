#pragma once

#include <fbjni/fbjni.h>
#include <string>
#include <string_view>

namespace margelo::nitro::audiobrowser {

/**
 * A Kotlin error crossing to C++, which the generated bridge rejects with (codegen's
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

  // Settles the rejection even when reading the message throws.
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

} // namespace margelo::nitro::audiobrowser
