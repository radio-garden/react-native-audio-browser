#pragma once

#include <NitroModules/NitroLogger.hpp>
#include <exception>
#include <fbjni/fbjni.h>
#include <string>
#include <utility>

namespace margelo::nitro::audiobrowser {

/**
 * Runs `settle`, which settles a Kotlin Promise from a C++ one, inside the app class loader, and logs
 * what it throws instead of throwing it: ~Promise runs a pending Promise's listeners, where a throw
 * (a class lookup on a thread tearing its runtime down, a Kotlin listener that throws) ends in
 * std::terminate. Not upstream: the vendored nitrogen's listeners call it
 * (codegen/syntax/kotlin/KotlinCxxBridgedType.js). Drop both once Nitro's JPromise does this itself.
 */
template <typename Settle>
inline void settleKotlinPromise(Settle&& settle) noexcept {
  try {
    facebook::jni::ThreadScope::WithClassLoader(std::forward<Settle>(settle));
  } catch (const std::exception& error) {
    Logger::nativeLog(LogLevel::Error, "AudioBrowser", std::string("Could not settle a Promise: ") + error.what());
  } catch (...) {
    Logger::nativeLog(LogLevel::Error, "AudioBrowser", "Could not settle a Promise");
  }
}

} // namespace margelo::nitro::audiobrowser
