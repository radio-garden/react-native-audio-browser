#pragma once

#include <NitroModules/JPromise.hpp>
#include <NitroModules/NitroLogger.hpp>
#include <exception>
#include <fbjni/fbjni.h>
#include <string>
#include <utility>

// Not upstream: the vendored nitrogen's listeners call these (codegen/syntax/kotlin/KotlinCxxBridgedType.js).
// Drop both once Nitro's JPromise settles inside the app class loader and catches what its settle throws.

namespace margelo::nitro::audiobrowser {

namespace detail {
  /** Logs `error`; logging that fails, as it can when memory runs out, is dropped. */
  inline void logSettleError(const char* what, const std::exception_ptr& error) noexcept {
    try {
      try {
        std::rethrow_exception(error);
      } catch (const std::exception& e) {
        Logger::nativeLog(LogLevel::Error, "AudioBrowser", std::string(what) + ": " + e.what());
        return;
      } catch (...) {
      }
      Logger::nativeLog(LogLevel::Error, "AudioBrowser", what);
    } catch (...) {
    }
  }

  template <typename Make, typename Apply>
  inline void settle(const facebook::jni::global_ref<JPromise::javaobject>& promise, Make&& make, Apply&& apply) noexcept {
    bool made = false;
    std::exception_ptr failure;
    try {
      facebook::jni::ThreadScope::WithClassLoader([&]() {
        auto argument = make();
        made = true;
        apply(argument);
      });
      return;
    } catch (...) {
      failure = std::current_exception();
    }
    if (made) {
      // The promise holds its result; a Kotlin listener (or the lookup that calls it) threw.
      logSettleError("A Promise listener failed", failure);
      return;
    }
    logSettleError("Could not settle a Promise", failure);
    try {
      facebook::jni::ThreadScope::WithClassLoader(
          [&]() { promise->cthis()->reject(facebook::jni::getJavaExceptionForCppException(failure)); });
    } catch (...) {
      logSettleError("Could not reject a Promise after its settle failed", std::current_exception());
    }
  }
} // namespace detail

/**
 * Resolves `promise` with what `makeValue` returns, inside the app class loader, and never throws: ~Promise
 * runs a pending C++ Promise's listeners, where a throw ends in std::terminate. When `makeValue` throws,
 * `promise` is rejected with that instead, so its awaiters do not wait forever. When a Kotlin listener throws,
 * `promise` has already settled: it is logged.
 */
template <typename MakeValue>
inline void resolveKotlinPromise(const facebook::jni::global_ref<JPromise::javaobject>& promise, MakeValue&& makeValue) noexcept {
  detail::settle(promise, std::forward<MakeValue>(makeValue), [&](const auto& value) { promise->cthis()->resolve(value); });
}

/** Rejects `promise` with what `makeError` returns, as resolveKotlinPromise resolves it. */
template <typename MakeError>
inline void rejectKotlinPromise(const facebook::jni::global_ref<JPromise::javaobject>& promise, MakeError&& makeError) noexcept {
  detail::settle(promise, std::forward<MakeError>(makeError), [&](const auto& error) { promise->cthis()->reject(error); });
}

} // namespace margelo::nitro::audiobrowser
