# Claude Development Notes

## Building

### Codegen (Nitrogen)

Run the forked Nitrogen codegen (located in `/codegen`):

```bash
yarn codegen
```

### Android

Compile Kotlin code from the example app directory:

```bash
cd apps/example-native/android && ./gradlew :react-native-audio-browser:compileDebugKotlin
```

## Breaking Changes Policy

This is an **alpha product** - we do not care about breaking changes. Feel free to make any necessary API changes to improve the codebase.

## Architecture

- Uses **Nitro Modules** most functionality can be sync / blocking in nature
- For user facing callbacks always work with a param object

## Nitro

### Important Callback Behaviors

**Callbacks work seamlessly across JS/native boundary:**
- Functions can be passed directly as parameters (no registration needed)
- Reference counting system holds strong references safely
- Can be called multiple times and stored in memory
- **Value-returning callbacks** become Promise<T> for thread safety
- **Void callbacks** (`() => void`) stay synchronous on native side
- **Sync<T> callbacks** are synchronous but dangerous (must call from JS thread only)

### Documentation

- [what-is-nitro.md](.claude/nitro/what-is-nitro.md) - Introduction and core concepts
- [hybrid-objects.md](.claude/nitro/hybrid-objects.md) - Core HybridObject interface patterns and constraints
- [sync-vs-async.md](.claude/nitro/sync-vs-async.md) - When to use synchronous vs asynchronous calls
- [performance-tips.md](.claude/nitro/performance-tips.md) - Optimization best practices for Nitro modules
- [nitrogen.md](.claude/nitro/nitrogen.md) - Code generation tool usage and configuration
- [how-to-build-a-nitro-module.md](.claude/nitro/how-to-build-a-nitro-module.md) - Step-by-step module creation guide
- [types/](.claude/nitro/types/) - Complete TypeScript type reference for native interfaces
  - [typing-system.md](.claude/nitro/types/typing-system.md) - Complete type mapping table (JS ↔ C++/Swift/Kotlin) and type safety rules
  - [primitives.md](.claude/nitro/types/primitives.md) - number/boolean/bigint → double/bool/int64_t with zero overhead
  - [strings.md](.claude/nitro/types/strings.md) - UTF-8 string handling across platforms
  - [arrays.md](.claude/nitro/types/arrays.md) - T[] → std::vector/Array with Kotlin PrimitiveArray optimizations
  - [custom-structs.md](.claude/nitro/types/custom-structs.md) - interface → native struct (eagerly converted, fully type-safe)
  - [custom-enums.md](.claude/nitro/types/custom-enums.md) - TypeScript enum vs union types with compile-time hash optimization
  - [custom-types.md](.claude/nitro/types/custom-types.md) - JSIConverter<T> for extending type system with custom C++ types
  - [optionals.md](.claude/nitro/types/optionals.md) - T? → std::optional/Swift?/Kotlin? with boxing considerations
  - [variants.md](.claude/nitro/types/variants.md) - A | B | C → std::variant with runtime overhead (avoid if possible)
  - [callbacks.md](.claude/nitro/types/callbacks.md) - Functions with reference counting, events, sync vs async callbacks
  - [promises.md](.claude/nitro/types/promises.md) - Promise<T> for async operations with thread switching
  - [dates.md](.claude/nitro/types/dates.md) - Date → time_point/Date/Instant with millisecond precision
  - [array-buffers.md](.claude/nitro/types/array-buffers.md) - Zero-copy binary data with ownership and threading considerations
  - [typed-maps.md](.claude/nitro/types/typed-maps.md) - Record<string, T> → Map/Dictionary (avoid: use arrays or structs instead)
  - [untyped-maps.md](.claude/nitro/types/untyped-maps.md) - AnyMap for JSON-like data (avoid: use typed structs instead)
  - [tuples.md](.claude/nitro/types/tuples.md) - [A, B, C] → fixed-size compile-time known types (efficient vs variants)
  - [hybrid-objects.md](.claude/nitro/types/hybrid-objects.md) - Passing HybridObject instances for interface-level abstraction
- [configuration-nitro-json.md](.claude/nitro/configuration-nitro-json.md) - Project configuration options
- [errors.md](.claude/nitro/errors.md) - Common error patterns and solutions
