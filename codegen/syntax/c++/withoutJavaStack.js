// Not upstream: a Kotlin error reaches C++, and from there JS, as android/src/main/cpp/JavaError.hpp,
// whose `what()` is the Kotlin error's message, where upstream passes on fbjni's
// `jni::JniException`, whose `what()` is the Java class and stack. The emitted names are
// unqualified: they rely on the generated code being in the header's namespace (`cxxNamespace` in
// nitro.json) and on `src/main/cpp` being an include directory (android/CMakeLists.txt).
//
// A re-vendor re-applies the call sites: a Kotlin promise's rejection and an error value
// (kotlin/KotlinCxxBridgedType.js), a hybrid object's getters, setters and methods
// (kotlin/FbjniHybridObject.js), and a Kotlin function's `invoke` (kotlin/KotlinFunction.js).
// Constructing the hybrid object (kotlin/KotlinHybridObjectRegistration.js) is not patched.
// Nitro's own JHybridObject calls Kotlin for JS `toString()`, which still reaches JS with fbjni's
// message, and for `dispose()` and the external memory size, which are noexcept: a throw there
// ends the app.
export const JAVA_ERROR_IMPORT = { language: 'c++', name: 'JavaError.hpp', space: 'user' };
export function withoutJavaStack(throwable) {
    return `JavaError(${throwable})`;
}
/** Calls a fbjni method with the given C++ arguments, so that a Throwable it throws leaves as JavaError. */
export function callJava(method, args) {
    return `callJava(${[method, ...args].join(', ')})`;
}
