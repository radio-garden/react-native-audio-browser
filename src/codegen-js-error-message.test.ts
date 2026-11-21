import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { NitroConfig } from '../codegen/config/NitroConfig.js'
import { createFbjniHybridObject } from '../codegen/syntax/kotlin/FbjniHybridObject.js'
import { KotlinCxxBridgedType } from '../codegen/syntax/kotlin/KotlinCxxBridgedType.js'
import { Method } from '../codegen/syntax/Method.js'
import { Parameter } from '../codegen/syntax/Parameter.js'
import { Property } from '../codegen/syntax/Property.js'
import { createSwiftCxxHelpers } from '../codegen/syntax/swift/SwiftCxxTypeHelper.js'
import { ErrorType } from '../codegen/syntax/types/ErrorType.js'
import { FunctionType } from '../codegen/syntax/types/FunctionType.js'
import { NamedWrappingType } from '../codegen/syntax/types/NamedWrappingType.js'
import { NumberType } from '../codegen/syntax/types/NumberType.js'
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
  /^\[\]\(const std::exception_ptr& __error\) -> std::exception_ptr \{\n.*catch \(const facebook::jsi::JSError& /s

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

// The vendored nitrogen's JniException unwrap (see codegen/syntax/c++/withoutJavaStack.js). These
// check the generated code only; what JavaError's `what()` gives is checked on a device.
const JAVA_ERROR = 'std::make_exception_ptr(JavaError('

/** A Kotlin error passed on as fbjni builds it, whose `what()` carries its class and stack. */
const RAW_JNI_EXCEPTION = /jni::JniException[ (]/

const toCppWithoutJavaStack = (code: string) =>
  !RAW_JNI_EXCEPTION.test(code) && code.includes(JAVA_ERROR)

/** What each Kotlin promise's rejection listener does, up to its closing `});`. */
const rejections = (code: string) =>
  code
    .split(
      'addOnRejectedListener([=](const jni::alias_ref<jni::JThrowable>& __throwable) {'
    )
    .slice(1)
    .map((listener) => {
      const end = listener.indexOf('});')
      expect(end).toBeGreaterThan(0)
      return listener.slice(0, end)
    })

describe('codegen: a Kotlin error crossing to C++ and JS', () => {
  it('rejects a Kotlin Promise with JavaError', () => {
    const code = new KotlinCxxBridgedType(
      new PromiseType(new StringType())
    ).parseFromKotlinToCpp('promise', 'c++')
    expect(toCppWithoutJavaStack(code)).toBe(true)
  })

  it('rejects a Kotlin Promise<void> with JavaError', () => {
    const code = new KotlinCxxBridgedType(
      new PromiseType(new VoidType())
    ).parseFromKotlinToCpp('promise', 'c++')
    expect(toCppWithoutJavaStack(code)).toBe(true)
  })

  it('passes a Kotlin error value on as JavaError', () => {
    const code = new KotlinCxxBridgedType(new ErrorType()).parseFromKotlinToCpp(
      'error',
      'c++'
    )
    expect(toCppWithoutJavaStack(code)).toBe(true)
  })

  it('includes JavaError.hpp wherever it is used', () => {
    for (const type of [new PromiseType(new StringType()), new ErrorType()]) {
      const imports = new KotlinCxxBridgedType(type).getRequiredImports('c++')
      expect(imports.map((i) => i.name)).toContain('JavaError.hpp')
    }
  })

  describe('in the committed bridge', () => {
    const files = readdirSync(ANDROID)
      .filter((file) => file.endsWith('.hpp') || file.endsWith('.cpp'))
      .map((file) => ({
        file,
        code: readFileSync(join(ANDROID, file), 'utf8')
      }))
    const listeners = files.flatMap(({ code }) => rejections(code))

    it('are all read', () => {
      expect(listeners.length).toBeGreaterThan(0)
    })

    // A generated .cpp includes only its own .hpp, which carries its includes.
    const includesJavaError = (file: string) =>
      [file, file.replace(/\.cpp$/, '.hpp')].some((f) =>
        files
          .find((other) => other.file === f)
          ?.code.includes('#include "JavaError.hpp"')
      )

    it('include JavaError.hpp wherever they use it', () => {
      expect(
        files
          .filter(({ code }) => code.includes('JavaError('))
          .filter(({ file }) => !includesJavaError(file))
          .map((f) => f.file)
      ).toEqual([])
    })

    it('reject with JavaError, never a raw JniException', () => {
      expect(listeners.filter((l) => !toCppWithoutJavaStack(l))).toEqual([])
      expect(
        files
          .filter(({ code }) => RAW_JNI_EXCEPTION.test(code))
          .map((f) => f.file)
      ).toEqual([])
    })
  })
})

const count = (code: string, text: string) => code.split(text).length - 1

/**
 * Whether every fbjni lookup of a Kotlin method or function is called through callJava, whose
 * catch (JavaError.hpp) gives JS the Throwable's message. What JS then sees is checked on a device.
 */
const callsJavaThroughCallJava = (code: string) => {
  const lookups = count(code, '->getMethod<')
  return (
    lookups > 0 &&
    count(code, 'callJava(method, ') === lookups &&
    !/\bmethod\(/.test(code) &&
    code.includes('#include "JavaError.hpp"')
  )
}

describe('codegen: a Kotlin hybrid method or property that throws synchronously', () => {
  const spec = {
    name: 'Thrower',
    properties: [
      new Property('state', new StringType(), true),
      new Property('volume', new NumberType(), false)
    ],
    methods: [
      new Method('stop', new VoidType(), []),
      new Method('find', new StringType(), [
        new Parameter('id', new StringType())
      ])
    ],
    baseTypes: [],
    config: NitroConfig.current
  }
  const cpp =
    createFbjniHybridObject(spec as never).find((f) => f.name.endsWith('.cpp'))
      ?.content ?? ''

  it('calls every getter, setter and method through callJava', () => {
    expect(count(cpp, '->getMethod<')).toBe(5)
    expect(callsJavaThroughCallJava(cpp)).toBe(true)
  })

  it('calls Kotlin through callJava in the committed bridge', () => {
    const files = readdirSync(ANDROID).filter((file) =>
      /^JHybrid\w+Spec\.cpp$/.test(file)
    )
    expect(files.length).toBeGreaterThan(0)
    expect(
      files.filter(
        (file) =>
          !callsJavaThroughCallJava(readFileSync(join(ANDROID, file), 'utf8'))
      )
    ).toEqual([])
  })
})
