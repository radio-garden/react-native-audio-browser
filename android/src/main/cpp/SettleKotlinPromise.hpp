#pragma once

#include <NitroModules/JPromise.hpp>
#include <NitroModules/NitroLogger.hpp>
#include <exception>
#include <fbjni/fbjni.h>
#include <string>
#include <utility>

namespace margelo::nitro::audiobrowser {

namespace detail {
  /** Logs the exception being handled; logging that fails, as it can when memory runs out, is dropped. */
  inline void logSettleError(const char* what) noexcept {
    try {
      try {
        throw;
      } catch (const std::exception& error) {
        Logger::nativeLog(LogLevel::Error, "AudioBrowser", std::string(what) + ": " + error.what());
        return;
      } catch (...) {
      }
      Logger::nativeLog(LogLevel::Error, "AudioBrowser", what);
    } catch (...) {
    }
  }
} // namespace detail

/**
 * Runs `settle`, which settles `promise` from a C++ Promise, inside the app class loader, and never
 * throws: ~Promise runs a pending Promise's listeners, where a throw (a class lookup on a thread tearing
 * its runtime down, a Kotlin listener that throws) ends in std::terminate. What `settle` throws is
 * logged, and `promise` is rejected with it, so that its awaiters, most of which have no timeout, do not
 * wait forever. JPromise cannot say whether `promise` already settled, so a listener that already ran
 * can be called again; what that throws is logged too.
 * Not upstream: the vendored nitrogen's listeners call it (codegen/syntax/kotlin/KotlinCxxBridgedType.js).
 * Drop both once Nitro's JPromise does this itself.
 */
template <typename Settle>
inline void settleKotlinPromise(const facebook::jni::global_ref<JPromise::javaobject>& promise, Settle&& settle) noexcept {
  std::exception_ptr failure;
  try {
    facebook::jni::ThreadScope::WithClassLoader(std::forward<Settle>(settle));
    return;
  } catch (...) {
    failure = std::current_exception();
    detail::logSettleError("Could not settle a Promise");
  }
  try {
    facebook::jni::ThreadScope::WithClassLoader(
        [&]() { promise->cthis()->reject(facebook::jni::getJavaExceptionForCppException(failure)); });
  } catch (...) {
    detail::logSettleError("Could not reject a Promise after its settle failed");
  }
}

} // namespace margelo::nitro::audiobrowser
