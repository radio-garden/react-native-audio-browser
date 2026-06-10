import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Regression guard for the original "async config callback returns an empty
 * config" bug.
 *
 * A callback typed `(...) => RequestConfig | Promise<RequestConfig>` lowers to a
 * Nitro `variant<RequestConfig, Promise<RequestConfig>>`. Because `RequestConfig`
 * is all-optional, its generated `canConvert` ALSO accepts a Promise (every field
 * reads `undefined`, which is "convertible"), and the struct arm is tried first —
 * so an async callback's returned Promise is decoded as an all-null struct.
 *
 * The fix is to never union a config struct with its own Promise: sync and async
 * are separate fields (`transform`/`transformSync`, `resolve`/`resolveSync`, and a
 * Promise-only resolver). This test fails if codegen ever re-emits that variant —
 * i.e. if someone reintroduces a `Config | Promise<Config>` callback return type.
 *
 * It's the one layer a pure-JS test can see: the bug itself lives in the native
 * JS↔C++ bridge and is invisible to unit tests on either side, but its *source*
 * is the generated variant, which we can assert against here.
 */
const GENERATED = join(process.cwd(), 'nitrogen', 'generated')

const BANNED: Array<{ re: RegExp; what: string }> = [
  {
    re: /Variant_RequestConfig_Promise_RequestConfig_/,
    what: 'RequestConfig | Promise<RequestConfig> (Swift/Kotlin variant)'
  },
  {
    re: /Variant_TransformableRequestConfig_Promise_TransformableRequestConfig_/,
    what: 'TransformableRequestConfig | Promise<TransformableRequestConfig> (Swift/Kotlin variant)'
  },
  { re: /std::variant<\s*RequestConfig\b/, what: 'std::variant<RequestConfig, ...> (C++)' },
  {
    re: /std::variant<\s*TransformableRequestConfig\b/,
    what: 'std::variant<TransformableRequestConfig, ...> (C++)'
  }
]

function* walk(dir: string): Generator<string> {
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry)
    if (statSync(p).isDirectory()) yield* walk(p)
    else yield p
  }
}

describe('codegen: no ambiguous config variant', () => {
  it('never generates variant<Config, Promise<Config>> for an all-optional config struct', () => {
    const offenders: string[] = []
    for (const file of walk(GENERATED)) {
      if (!/\.(swift|hpp|cpp|kt)$/.test(file)) continue
      const text = readFileSync(file, 'utf8')
      const hit = BANNED.find(({ re }) => re.test(text))
      if (hit) offenders.push(`${file}  →  ${hit.what}`)
    }
    expect(
      offenders,
      'A `Config | Promise<Config>` callback union was reintroduced — it regenerates the ' +
        'ambiguous Nitro variant whose struct arm swallows a Promise (async returns become {}). ' +
        'Use separate sync/async fields instead. Offending generated files:\n' +
        offenders.join('\n')
    ).toEqual([])
  })
})
