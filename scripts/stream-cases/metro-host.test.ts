import { describe, expect, it } from 'vitest'
import { pointAppAtMetro, withMetroHost, type Adb } from './metro-host'

const head = `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>`

describe('withMetroHost', () => {
  it('writes a preferences file when the app has none', () => {
    expect(withMetroHost('', 'localhost:8097')).toBe(
      `${head}\n<map>\n    <string name="debug_http_host">localhost:8097</string>\n</map>\n`
    )
  })

  it('adds the host and keeps the other settings', () => {
    const prefs = `${head}\n<map>\n    <boolean name="js_dev_mode_debug" value="true" />\n</map>\n`
    expect(withMetroHost(prefs, 'localhost:8097')).toBe(
      `${head}\n<map>\n    <boolean name="js_dev_mode_debug" value="true" />\n    <string name="debug_http_host">localhost:8097</string>\n</map>\n`
    )
  })

  it('replaces a host saved before', () => {
    const prefs = `${head}\n<map>\n    <string name="debug_http_host">10.0.2.2:8081</string>\n    <boolean name="fps_debug" value="false" />\n</map>\n`
    expect(withMetroHost(prefs, 'localhost:8097')).toBe(
      `${head}\n<map>\n    <boolean name="fps_debug" value="false" />\n    <string name="debug_http_host">localhost:8097</string>\n</map>\n`
    )
  })

  it('reads an empty map', () => {
    expect(withMetroHost(`${head}\n<map />\n`, 'localhost:8097')).toBe(
      withMetroHost('', 'localhost:8097')
    )
  })

  it('keeps a value that spans lines as it was', () => {
    const prefs = `${head}\n<map>\n    <string name="note">one\n\n  two</string>\n</map>\n`
    expect(withMetroHost(prefs, 'localhost:8097')).toBe(
      `${head}\n<map>\n    <string name="note">one\n\n  two</string>\n    <string name="debug_http_host">localhost:8097</string>\n</map>\n`
    )
  })
})

function device(file: string | undefined) {
  const state = { file, running: true }
  const adb: Adb = (args, input) => {
    const command = args.join(' ')
    if (command.includes('force-stop')) state.running = false
    else if (command.includes('|| cat')) return state.file ?? ''
    else {
      expect(state.running, command).toBe(false)
      if (command.includes('mv ')) state.file = input
      else if (command.includes('rm -f shared_prefs')) state.file = undefined
      else throw new Error(`unexpected: ${command}`)
    }
    return ''
  }
  return { state, adb }
}

describe('pointAppAtMetro', () => {
  const app = 'com.example'

  it('points a stopped app at Metro, and its undo removes the file it had none of', () => {
    const { state, adb } = device(undefined)
    const undo: (() => void)[] = []
    pointAppAtMetro(adb, app, 'localhost:8097', undo)
    expect(state.file).toBe(withMetroHost('', 'localhost:8097'))
    state.running = true
    undo.forEach((step) => step())
    expect(state).toEqual({ file: undefined, running: false })
  })

  it('undoes to the preferences it found, the app stopped first', () => {
    const before = `${head}\n<map>\n    <string name="debug_http_host">10.0.2.2:8081</string>\n</map>\n`
    const { state, adb } = device(before)
    const undo: (() => void)[] = []
    pointAppAtMetro(adb, app, 'localhost:8097', undo)
    expect(state.file).toContain('localhost:8097')
    state.running = true
    undo.forEach((step) => step())
    expect(state).toEqual({ file: before, running: false })
  })

  it('can undo even when its own write fails', () => {
    const { state, adb } = device(undefined)
    const undo: (() => void)[] = []
    const failing: Adb = (args, input) => {
      if (args.join(' ').includes('mv ') && undo.length === 1 && !state.file) {
        state.file = 'partial'
        throw new Error('device gone')
      }
      return adb(args, input)
    }
    expect(() =>
      pointAppAtMetro(failing, app, 'localhost:8097', undo)
    ).toThrow()
    undo.forEach((step) => step())
    expect(state.file).toBeUndefined()
  })
})
