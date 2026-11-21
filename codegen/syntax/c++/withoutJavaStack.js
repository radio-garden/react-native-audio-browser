// Not upstream: rejects with android/src/main/cpp/JavaError.hpp, whose `what()` is the Kotlin
// error's message, where upstream builds a plain `jni::JniException`, whose `what()` is the Java
// class and stack. The emitted `JavaError` is unqualified: it relies on the generated code being in
// the header's namespace (`cxxNamespace` in nitro.json) and on `src/main/cpp` being an include
// directory (android/CMakeLists.txt).
export const JAVA_ERROR_IMPORT = { language: 'c++', name: 'JavaError.hpp', space: 'user' };
export function withoutJavaStack(throwable) {
    return `JavaError(${throwable})`;
}
