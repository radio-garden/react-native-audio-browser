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

  template <typename Make, typename SettleWith>
  inline void settle(const facebook::jni::global_ref<JPromise::javaobject>& promise, Make&& make, SettleWith settleWith) noexcept {
    bool made = false;
    std::exception_ptr failure;
    try {
      facebook::jni::ThreadScope::WithClassLoader([&]() {
        JPromise* target = promise->cthis();
        auto argument = make();
        made = true;
        (target->*settleWith)(argument);
      });
      return;
    } catch (...) {
      failure = std::current_exception();
    }
    if (made) {
      // A Kotlin listener (or the lookup that calls it) threw, or JPromise could not store the result.
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
 * `promise` has already settled: it is logged, as is JPromise failing to store the value when memory runs out.
 */
template <typename MakeValue>
inline void resolveKotlinPromise(const facebook::jni::global_ref<JPromise::javaobject>& promise, MakeValue&& makeValue) noexcept {
  detail::settle(promise, std::forward<MakeValue>(makeValue), &JPromise::resolve);
}

/** Rejects `promise` with what `makeError` returns, as resolveKotlinPromise resolves it. */
template <typename MakeError>
inline void rejectKotlinPromise(const facebook::jni::global_ref<JPromise::javaobject>& promise, MakeError&& makeError) noexcept {
  detail::settle(promise, std::forward<MakeError>(makeError), &JPromise::reject);
}

} // namespace margelo::nitro::audiobrowser
