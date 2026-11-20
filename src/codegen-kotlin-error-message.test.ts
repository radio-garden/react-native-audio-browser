import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { withoutJavaStack } from '../codegen/syntax/c++/withoutJavaStack.js'
import { KotlinCxxBridgedType } from '../codegen/syntax/kotlin/KotlinCxxBridgedType.js'
import { ErrorType } from '../codegen/syntax/types/ErrorType.js'
import { PromiseType } from '../codegen/syntax/types/PromiseType.js'
import { StringType } from '../codegen/syntax/types/StringType.js'

const ANDROID = join(process.cwd(), 'nitrogen/generated/android/c++')
const LISTENER =
  'addOnRejectedListener([=](const jni::alias_ref<jni::JThrowable>& __throwable) {'

const squashed = (code: string) => code.replace(/\s+/g, ' ').trim()
const UNWRAP = squashed(withoutJavaStack('__throwable'))

/** What each rejected Kotlin Promise hands its C++ Promise. */
const rejections = (code: string) =>
  code
    .split(LISTENER)
    .slice(1)
    .map((listener) =>
      squashed(
        listener.match(/__promise->reject\(([\s\S]*?)\);\n\s*\}\);/)?.[1] ?? ''
      )
    )

describe('codegen: a Kotlin Throwable crossing to C++', () => {
  it('keeps its message, or its toString() when it has none', () => {
    expect(UNWRAP).toContain(
      'auto __message = __throwable->getMessage(); return std::make_exception_ptr(std::runtime_error(__message ? __message->toStdString() : __throwable->toString()));'
    )
  })

  it('reaches C++ without its stack from a rejected Kotlin Promise', () => {
    const code = new KotlinCxxBridgedType(
      new PromiseType(new StringType())
    ).parseFromKotlinToCpp('promise', 'c++')
    expect(rejections(code)).toEqual([UNWRAP])
  })

  it('reaches C++ without its stack as a value', () => {
    const code = new KotlinCxxBridgedType(new ErrorType()).parseFromKotlinToCpp(
      'error',
      'c++'
    )
    expect(squashed(code)).toBe(squashed(withoutJavaStack('error')))
  })

  describe('in the committed bridge', () => {
    const code = readdirSync(ANDROID)
      .filter((file) => file.endsWith('.hpp') || file.endsWith('.cpp'))
      .map((file) => readFileSync(join(ANDROID, file), 'utf8'))
    const android = code.flatMap(rejections)

    it('are all read', () => {
      const listeners = code.flatMap((file) => [
        ...file.matchAll(/addOnRejectedListener\(\[[^\]]*\]\([^)]*JThrowable/g)
      ])
      expect(android.length).toBeGreaterThan(0)
      expect(android).toHaveLength(listeners.length)
    })

    it('reach C++ without their stack', () => {
      expect(android.filter((rejection) => rejection !== UNWRAP)).toEqual([])
    })
  })
})
