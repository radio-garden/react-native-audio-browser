import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The version floors in the Getting Started guide are prose copies of the
 * build files. This fails when a build file moves and the guide doesn't.
 */
const ROOT = process.cwd()
const read = (path: string) => readFileSync(join(ROOT, path), 'utf8')

const pkg = JSON.parse(read('package.json')) as {
  peerDependencies: Record<string, string>
}
const guide = read('website/guide/getting-started.md')

/** `>=0.76.0` → `0.76`, `^4.7.9` → `4.7.9`: the floor as the guide writes it. */
function peerFloor(name: string, parts: number): string {
  const range = pkg.peerDependencies[name]
  const version = range?.match(/\d+(\.\d+)*/)?.[0]
  if (!version) throw new Error(`no peer range for ${name}`)
  return version.split('.').slice(0, parts).join('.')
}

function stated(pattern: RegExp): string {
  const match = guide.match(pattern)
  if (!match?.[1])
    throw new Error(`getting-started.md no longer states ${pattern}`)
  return match[1]
}

describe('getting-started.md version floors', () => {
  it('React Native matches the peer range', () => {
    expect(stated(/React Native (\d+\.\d+) or newer/)).toBe(
      peerFloor('react-native', 2)
    )
  })

  it('React matches the peer range', () => {
    expect(stated(/React (\d+\.\d+)\+/)).toBe(peerFloor('react', 2))
  })

  it('react-native-nitro-modules matches the peer range', () => {
    expect(stated(/`react-native-nitro-modules` (\d+\.\d+) or later/)).toBe(
      peerFloor('react-native-nitro-modules', 2)
    )
  })

  it('shaka-player matches the peer range, major and floor', () => {
    const range = pkg.peerDependencies['shaka-player']
    expect(range?.startsWith('^')).toBe(true)
    expect(stated(/shaka-player`\]\([^)]*\) (\d+) \(/)).toBe(
      peerFloor('shaka-player', 1)
    )
    expect(
      stated(/shaka-player`\]\([^)]*\) \d+ \((\d+\.\d+\.\d+) or later\)/)
    ).toBe(peerFloor('shaka-player', 3))
  })

  it('iOS matches the podspec', () => {
    const ios = read('AudioBrowser.podspec').match(/:ios => "(\d+)/)?.[1]
    expect(stated(/iOS (\d+)\+/)).toBe(ios)
  })

  it('Android matches minSdkVersion', () => {
    const sdk = read('android/gradle.properties').match(
      /AudioBrowser_minSdkVersion=(\d+)/
    )?.[1]
    expect(stated(/Android API (\d+)\+/)).toBe(sdk)
  })
})
