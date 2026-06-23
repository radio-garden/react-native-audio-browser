import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { updateOptions } from './options'
import { nativeBrowser } from '../../native'

vi.mock('../../native', () => ({
  nativeBrowser: { updateOptions: vi.fn() }
}))

describe('updateOptions', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => vi.restoreAllMocks())

  it('forwards a nested ios bag to the native layer unchanged', () => {
    updateOptions({ ios: { carPlayNowPlayingButtons: ['favorite'] } })
    expect(nativeBrowser.updateOptions).toHaveBeenCalledWith({
      ios: { carPlayNowPlayingButtons: ['favorite'] }
    })
  })

  it('warns on more than 5 CarPlay now-playing buttons', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    updateOptions({
      ios: {
        carPlayNowPlayingButtons: [
          'shuffle',
          'repeat',
          'favorite',
          'playback-rate',
          'shuffle',
          'repeat'
        ]
      }
    })
    expect(warn.mock.calls.some(([m]) => String(m).includes('at most 5'))).toBe(
      true
    )
  })
})
