import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { KotlinCxxBridgedType } from '../codegen/syntax/kotlin/KotlinCxxBridgedType.js'
import { PromiseType } from '../codegen/syntax/types/PromiseType.js'
import { StringType } from '../codegen/syntax/types/StringType.js'
import { VoidType } from '../codegen/syntax/types/VoidType.js'

// The vendored nitrogen's settle listeners (see android/src/main/cpp/SettleKotlinPromise.hpp).
const GENERATED = join(process.cwd(), 'nitrogen/generated/android/c++')

/** The bodies of the listeners that settle a Kotlin Promise from a C++ one. */
const cppToKotlinListeners = (code: string) =>
  [
    ...code.matchAll(
      /^( *)\S*->addOn(?:Resolved|Rejected)Listener\(\[=\]\(([^)]*)\) \{\n([\s\S]*?)\n\1\}\);$/gm
    )
  ]
    .filter(([, , params]) => !params?.includes('jni::alias_ref'))
    .map(([, , , body]) => body ?? '')

/** Listeners whose body is anything but one settleKotlinPromise call. */
const unguarded = (code: string) =>
  cppToKotlinListeners(code).filter((body) => {
    const lines = body.split('\n')
    const indent = lines[0]?.match(/^ */)?.[0] ?? ''
    return (
      lines[0] !== `${indent}settleKotlinPromise([&]() {` ||
      lines.at(-1) !== `${indent}});`
    )
  })

describe('codegen: a C++ Promise settling a Kotlin Promise', () => {
  it.each([
    ['a value', new StringType()],
    ['void', new VoidType()]
  ])('settles it for %s through settleKotlinPromise', (_, result) => {
    const code = new KotlinCxxBridgedType(
      new PromiseType(result)
    ).parseFromCppToKotlin('promise', 'c++')
    expect(cppToKotlinListeners(code)).toHaveLength(2)
    expect(unguarded(code)).toEqual([])
  })

  describe('in the committed bridge', () => {
    const files = readdirSync(GENERATED)
      .filter((file) => file.endsWith('.hpp'))
      .map((file) => readFileSync(join(GENERATED, file), 'utf8'))

    it('are all read', () => {
      expect(files.flatMap(cppToKotlinListeners).length).toBeGreaterThan(0)
    })

    it('settle it through settleKotlinPromise', () => {
      expect(files.flatMap(unguarded)).toEqual([])
    })
  })
})
