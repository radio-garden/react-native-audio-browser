import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { KotlinCxxBridgedType } from '../codegen/syntax/kotlin/KotlinCxxBridgedType.js'
import { PromiseType } from '../codegen/syntax/types/PromiseType.js'
import { StringType } from '../codegen/syntax/types/StringType.js'
import { VoidType } from '../codegen/syntax/types/VoidType.js'

// The vendored nitrogen's class-loader scope (see codegen/syntax/kotlin/KotlinCxxBridgedType.js).
const GENERATED = join(process.cwd(), 'nitrogen/generated/android/c++')

/** The listeners that settle a Kotlin Promise from a C++ one, each with the line its body opens with. */
const cppToKotlinListeners = (code: string) =>
  [
    ...code.matchAll(
      /->addOn(?:Resolved|Rejected)Listener\(\[=\]\(([^)]*)\) \{\n(.*)\n/g
    )
  ]
    .filter(([, params]) => !params?.includes('jni::alias_ref'))
    .map(([, , firstLine]) => firstLine ?? '')

const unscoped = (code: string) =>
  cppToKotlinListeners(code).filter(
    (line) => !line.includes('jni::ThreadScope::WithClassLoader(')
  )

describe('codegen: a C++ Promise settling a Kotlin Promise', () => {
  it.each([
    ['a value', new StringType()],
    ['void', new VoidType()]
  ])('settles it for %s inside the app class loader', (_, result) => {
    const code = new KotlinCxxBridgedType(
      new PromiseType(result)
    ).parseFromCppToKotlin('promise', 'c++')
    expect(cppToKotlinListeners(code)).toHaveLength(2)
    expect(unscoped(code)).toEqual([])
  })

  describe('in the committed bridge', () => {
    const files = readdirSync(GENERATED)
      .filter((file) => file.endsWith('.hpp'))
      .map((file) => readFileSync(join(GENERATED, file), 'utf8'))

    it('are all read', () => {
      expect(files.flatMap(cppToKotlinListeners).length).toBeGreaterThan(0)
    })

    it('settle it inside the app class loader', () => {
      expect(files.flatMap(unscoped)).toEqual([])
    })
  })
})
