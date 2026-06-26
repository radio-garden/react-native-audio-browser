import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MediaSessionManager } from './MediaSessionManager'
import type { MediaSessionActions } from './MediaSessionManager'

type Handler = ((details: any) => void) | null

class FakeMediaMetadata {
  title?: string
  artist?: string
  album?: string
  artwork: { src: string }[]
  constructor(init: {
    title?: string
    artist?: string
    album?: string
    artwork?: { src: string }[]
  }) {
    this.title = init.title
    this.artist = init.artist
    this.album = init.album
    this.artwork = init.artwork ?? []
  }
}

function makeFakeSession() {
  const handlers = new Map<string, Handler>()
  const setPositionState = vi.fn()
  const session = {
    metadata: null as FakeMediaMetadata | null,
    playbackState: 'none' as 'none' | 'paused' | 'playing',
    setActionHandler: vi.fn((action: string, handler: Handler) => {
      handlers.set(action, handler)
    }),
    setPositionState
  }
  return { session, handlers, setPositionState }
}

function makeActions(): MediaSessionActions {
  return {
    play: vi.fn(),
    pause: vi.fn(),
    stop: vi.fn(),
    next: vi.fn(),
    previous: vi.fn(),
    seek: vi.fn(),
    jumpForward: vi.fn(),
    jumpBackward: vi.fn()
  }
}

describe('MediaSessionManager', () => {
  let fake: ReturnType<typeof makeFakeSession>

  beforeEach(() => {
    fake = makeFakeSession()
    vi.stubGlobal('navigator', { mediaSession: fake.session })
    vi.stubGlobal('MediaMetadata', FakeMediaMetadata)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('registers action handlers that route to the provided actions', () => {
    const actions = makeActions()
    new MediaSessionManager(actions)

    fake.handlers.get('play')?.(undefined)
    fake.handlers.get('pause')?.(undefined)
    fake.handlers.get('stop')?.(undefined)
    fake.handlers.get('nexttrack')?.(undefined)
    fake.handlers.get('previoustrack')?.(undefined)

    expect(actions.play).toHaveBeenCalledOnce()
    expect(actions.pause).toHaveBeenCalledOnce()
    expect(actions.stop).toHaveBeenCalledOnce()
    expect(actions.next).toHaveBeenCalledOnce()
    expect(actions.previous).toHaveBeenCalledOnce()
  })

  it('routes seekto with the requested seek time', () => {
    const actions = makeActions()
    new MediaSessionManager(actions)

    fake.handlers.get('seekto')?.({ seekTime: 42 })

    expect(actions.seek).toHaveBeenCalledWith(42)
  })

  it('ignores seekto without a seek time', () => {
    const actions = makeActions()
    new MediaSessionManager(actions)

    fake.handlers.get('seekto')?.({})

    expect(actions.seek).not.toHaveBeenCalled()
  })

  it('uses the seek offset for seekforward/seekbackward, falling back to a default', () => {
    const actions = makeActions()
    new MediaSessionManager(actions)

    fake.handlers.get('seekforward')?.({ seekOffset: 30 })
    fake.handlers.get('seekbackward')?.({})

    expect(actions.jumpForward).toHaveBeenCalledWith(30)
    expect(actions.jumpBackward).toHaveBeenCalledWith(10)
  })

  it('publishes metadata to the session', () => {
    new MediaSessionManager(makeActions()).setMetadata({
      title: 'Live Set',
      artist: 'Station FM',
      album: 'Radio',
      artwork: 'https://api.example.com/art.png'
    })

    const metadata = fake.session.metadata as FakeMediaMetadata
    expect(metadata.title).toBe('Live Set')
    expect(metadata.artist).toBe('Station FM')
    expect(metadata.album).toBe('Radio')
    expect(metadata.artwork).toEqual([{ src: 'https://api.example.com/art.png' }])
  })

  it('omits artwork when none is provided', () => {
    new MediaSessionManager(makeActions()).setMetadata({ title: 'No Art' })
    const metadata = fake.session.metadata as FakeMediaMetadata
    expect(metadata.artwork).toEqual([])
  })

  it('reflects playback state', () => {
    const manager = new MediaSessionManager(makeActions())
    manager.setPlaybackState('playing')
    expect(fake.session.playbackState).toBe('playing')
    manager.setPlaybackState('paused')
    expect(fake.session.playbackState).toBe('paused')
  })

  it('reports position state for finite, seekable durations', () => {
    const manager = new MediaSessionManager(makeActions())
    manager.setPositionState({ duration: 180, position: 30, playbackRate: 1 })
    expect(fake.setPositionState).toHaveBeenCalledWith({
      duration: 180,
      position: 30,
      playbackRate: 1
    })
  })

  it('clears position state for live streams (infinite/zero duration)', () => {
    const manager = new MediaSessionManager(makeActions())
    manager.setPositionState({ duration: Infinity, position: 30, playbackRate: 1 })
    manager.setPositionState({ duration: 0, position: 0, playbackRate: 1 })
    // Called with no argument resets the position state.
    expect(fake.setPositionState).toHaveBeenCalledTimes(2)
    expect(fake.setPositionState).toHaveBeenNthCalledWith(1)
    expect(fake.setPositionState).toHaveBeenNthCalledWith(2)
  })

  it('clamps position to within the duration', () => {
    const manager = new MediaSessionManager(makeActions())
    manager.setPositionState({ duration: 100, position: 150, playbackRate: 1 })
    expect(fake.setPositionState).toHaveBeenCalledWith({
      duration: 100,
      position: 100,
      playbackRate: 1
    })
  })

  it('clamps a negative position up to zero', () => {
    const manager = new MediaSessionManager(makeActions())
    manager.setPositionState({ duration: 100, position: -5, playbackRate: 1 })
    expect(fake.setPositionState).toHaveBeenCalledWith({
      duration: 100,
      position: 0,
      playbackRate: 1
    })
  })

  it('coerces a non-positive playback rate to 1 (the API rejects rate 0)', () => {
    const manager = new MediaSessionManager(makeActions())
    manager.setPositionState({ duration: 100, position: 10, playbackRate: 0 })
    expect(fake.setPositionState).toHaveBeenCalledWith({
      duration: 100,
      position: 10,
      playbackRate: 1
    })
  })

  it('dispose clears handlers, metadata and playback state', () => {
    const manager = new MediaSessionManager(makeActions())
    manager.setMetadata({ title: 'x' })
    manager.setPlaybackState('playing')

    manager.dispose()

    expect(fake.session.metadata).toBeNull()
    expect(fake.session.playbackState).toBe('none')
    // Every registered action gets cleared back to null.
    expect(fake.handlers.get('play')).toBeNull()
  })

  it('is a no-op when the Media Session API is unavailable', () => {
    vi.stubGlobal('navigator', {})
    const actions = makeActions()
    expect(() => {
      const manager = new MediaSessionManager(actions)
      manager.setMetadata({ title: 'x' })
      manager.setPlaybackState('playing')
      manager.setPositionState({ duration: 1, position: 0, playbackRate: 1 })
      manager.dispose()
    }).not.toThrow()
  })
})
