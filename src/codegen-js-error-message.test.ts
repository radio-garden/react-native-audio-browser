import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { KotlinCxxBridgedType } from '../codegen/syntax/kotlin/KotlinCxxBridgedType.js'
import { createSwiftCxxHelpers } from '../codegen/syntax/swift/SwiftCxxTypeHelper.js'
import { ErrorType } from '../codegen/syntax/types/ErrorType.js'
import { FunctionType } from '../codegen/syntax/types/FunctionType.js'
import { NamedWrappingType } from '../codegen/syntax/types/NamedWrappingType.js'
import { PromiseType } from '../codegen/syntax/types/PromiseType.js'
import { StringType } from '../codegen/syntax/types/StringType.js'
import { VoidType } from '../codegen/syntax/types/VoidType.js'

// The vendored nitrogen's JSError unwrap (see codegen/syntax/c++/withoutJSStack.js).
const ANDROID = join(process.cwd(), 'nitrogen/generated/android/c++')
const IOS_BRIDGE = join(
  process.cwd(),
  'nitrogen/generated/ios/AudioBrowser-Swift-Cxx-Bridge.cpp'
)
const UNWRAP =
  /^\[&\]\(\) -> std::exception_ptr \{\n.*catch \(const facebook::jsi::JSError& /s

/** What each C++ error is converted from on its way to Kotlin. */
const toKotlin = (code: string) =>
  code.split('jni::getJavaExceptionForCppException(').slice(1)

/** What each C++ error is converted from on its way to a Swift closure. */
const toSwift = (code: string) =>
  [
    ...code.matchAll(
      /const std::exception_ptr& error\) mutable -> void \{\n\s*swiftClosure\.call\(([\s\S]*?)\);\n\s*\};/g
    )
  ].map(([, argument]) => argument ?? '')

const unwrapped = (argument: string) => UNWRAP.test(argument.trim())

describe('codegen: a JS error crossing to Kotlin or Swift', () => {
  it('reaches Kotlin without its stack from a rejected C++ Promise', () => {
    const code = new KotlinCxxBridgedType(
      new PromiseType(new StringType())
    ).parseFromCppToKotlin('promise', 'c++')
    expect(toKotlin(code)).toHaveLength(1)
    expect(toKotlin(code).every(unwrapped)).toBe(true)
  })

  it('reaches Kotlin without its stack as a value', () => {
    const code = new KotlinCxxBridgedType(new ErrorType()).parseFromCppToKotlin(
      'error',
      'c++'
    )
    expect(toKotlin(code).every(unwrapped)).toBe(true)
  })

  it('reaches a Swift closure without its stack', () => {
    const rejecter = new FunctionType(new VoidType(), [
      new NamedWrappingType('error', new ErrorType())
    ])
    const code = createSwiftCxxHelpers(rejecter)?.cxxImplementation?.code ?? ''
    expect(toSwift(code)).toHaveLength(1)
    expect(toSwift(code).every(unwrapped)).toBe(true)
  })

  describe('in the committed bridge', () => {
    const android = readdirSync(ANDROID)
      .filter((file) => file.endsWith('.hpp'))
      .flatMap((file) => toKotlin(readFileSync(join(ANDROID, file), 'utf8')))
    const ios = toSwift(readFileSync(IOS_BRIDGE, 'utf8'))

    it('are all read', () => {
      expect(android.length).toBeGreaterThan(0)
      expect(ios.length).toBeGreaterThan(0)
    })

    it('reach Kotlin and Swift without their stack', () => {
      expect(android.filter((a) => !unwrapped(a))).toEqual([])
      expect(ios.filter((a) => !unwrapped(a))).toEqual([])
    })
  })
})
