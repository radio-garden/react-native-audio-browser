/** @vitest-environment happy-dom */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NativeAudioBrowser } from '../NativeAudioBrowser'
// Installs the Shaka mock that lets the real `setupPlayer()` run.
import './lifecycleHarness'

type Handler = ((details: unknown) => void) | null

function stubMediaSession(): Map<string, Handler> {
  const handlers = new Map<string, Handler>()
  vi.stubGlobal('navigator', {
    onLine: true,
    mediaSession: {
      metadata: null,
      playbackState: 'none',
      setActionHandler: (action: string, handler: Handler) =>
        handlers.set(action, handler),
      setPositionState: () => {}
    }
  })
  vi.stubGlobal('MediaMetadata', class {})
  return handlers
}

describe('Media Session handlers', () => {
  let handlers: Map<string, Handler>

  beforeEach(() => {
    ;(globalThis as unknown as { __DEV__: boolean }).__DEV__ = false
    document.body.innerHTML = ''
    handlers = stubMediaSession()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('are not registered before setupPlayer()', () => {
    new NativeAudioBrowser()

    expect([...handlers.keys()]).toEqual([])
  })

  it('are registered by setupPlayer()', async () => {
    const browser = new NativeAudioBrowser()
    const onRemotePause = vi.fn()
    browser.onRemotePause = onRemotePause

    await browser.setupPlayer({})
    handlers.get('pause')?.(undefined)

    expect(onRemotePause).toHaveBeenCalledOnce()
  })
})
