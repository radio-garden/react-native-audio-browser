import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { createSwiftCxxHelpers } from '../codegen/syntax/swift/SwiftCxxTypeHelper.js'
import { FunctionType } from '../codegen/syntax/types/FunctionType.js'
import { NamedWrappingType } from '../codegen/syntax/types/NamedWrappingType.js'
import { NumberType } from '../codegen/syntax/types/NumberType.js'
import { PromiseType } from '../codegen/syntax/types/PromiseType.js'
import { StringType } from '../codegen/syntax/types/StringType.js'

// The vendored nitrogen's catch for margelo/nitro#1481 (see codegen/syntax/swift/SwiftCxxTypeHelper.js).
const BRIDGE = join(
  process.cwd(),
  'nitrogen/generated/ios/AudioBrowser-Swift-Cxx-Bridge.hpp'
)

const promiseWrappers = (header: string) =>
  [
    ...header.matchAll(
      /class (Func_std__shared_ptr_Promise_\w+_Wrapper) final \{[\s\S]*?\n\s*inline .* call\(.*\) const noexcept \{\n([\s\S]*?)\n\s*\}\n/g
    )
  ].map(([, name, body]) => ({ name, body: body ?? '' }))

const unguarded = (header: string) =>
  promiseWrappers(header)
    .filter(
      ({ body }) =>
        !/catch \(\.\.\.\) \{\s*return Promise<.*>::rejected\(std::current_exception\(\)\);/.test(
          body
        )
    )
    .map(({ name }) => name)

describe('codegen: Promise-returning JS callbacks called from Swift', () => {
  it('are generated to reject instead of throwing across noexcept', () => {
    const callback = new FunctionType(new PromiseType(new StringType()), [
      new NamedWrappingType('value', new NumberType())
    ])
    const header = createSwiftCxxHelpers(callback)?.cxxHeader.code ?? ''
    expect(promiseWrappers(header)).toHaveLength(1)
    expect(unguarded(header)).toEqual([])
  })

  describe('in the committed bridge', () => {
    const header = readFileSync(BRIDGE, 'utf8')

    it('are all read', () => {
      const declared =
        header.match(/class Func_std__shared_ptr_Promise_\w+_Wrapper final/g) ??
        []
      expect(declared.length).toBeGreaterThan(0)
      expect(promiseWrappers(header)).toHaveLength(declared.length)
    })

    it('reject instead of throwing across noexcept', () => {
      expect(unguarded(header)).toEqual([])
    })
  })
})
