import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'

// The vendored nitrogen's catch for margelo/nitro#1481, which a re-vendor could drop.
const BRIDGE = join(
  process.cwd(),
  'nitrogen/generated/ios/AudioBrowser-Swift-Cxx-Bridge.hpp'
)

const promiseWrappers = (header: string) =>
  [
    ...header.matchAll(
      /class (Func_std__shared_ptr_Promise_\w+_Wrapper) final \{[\s\S]*?\n {4}inline .* call\(.*\) const noexcept \{\n([\s\S]*?)\n {4}\}\n/g
    )
  ].map(([, name, body]) => ({ name, body: body ?? '' }))

describe('codegen: Promise-returning JS callbacks called from Swift', () => {
  const header = readFileSync(BRIDGE, 'utf8')
  const wrappers = promiseWrappers(header)

  it('reads every wrapper', () => {
    const declared =
      header.match(/class Func_std__shared_ptr_Promise_\w+_Wrapper final/g) ??
      []
    expect(declared.length).toBeGreaterThan(0)
    expect(wrappers.length).toBe(declared.length)
  })

  it('reject instead of throwing across noexcept', () => {
    const unguarded = wrappers
      .filter(
        ({ body }) =>
          !/catch \(\.\.\.\) \{\s*return Promise<.*>::rejected\(std::current_exception\(\)\);/.test(
            body
          )
      )
      .map(({ name }) => name)
    expect(unguarded).toEqual([])
  })
})
