import NitroModules

// Works around a Nitro Modules issue: nitrogen-generated Swift calls .map() on std::vector<T>,
// which needs CxxRandomAccessCollection. Swift's C++ interop synthesizes that conformance lazily,
// so an incremental build can fail with "Value of type 'std::vector<T>' has no member 'map'".
// Declare it here for each vector the generated code maps; find them with
//   grep -r '\.map(' nitrogen/generated/ios/swift/ | grep std__vector
extension margelo.nitro.audiobrowser.bridge.swift.std__vector_Track_: @retroactive CxxRandomAccessCollection {}
