import { describe, expect, it } from 'vitest'
import { Player } from './Player'

// Refuses what a media element refuses: a non-finite volume, and one outside
// 0–1.
class VolumeTestPlayer extends Player {
  constructor(initialVolume: number) {
    super()
    let current = initialVolume
    this.element = {
      get volume() {
        return current
      },
      set volume(value: number) {
        if (!Number.isFinite(value)) throw new TypeError('non-finite volume')
        if (value < 0 || value > 1) throw new RangeError('volume outside 0–1')
        current = value
      }
    } as unknown as HTMLMediaElement
  }
}

describe('Player.setVolume', () => {
  it('sets a level inside 0–1 as given', () => {
    const player = new VolumeTestPlayer(1)
    player.setVolume(0.25)
    expect(player.getVolume()).toBe(0.25)
  })

  it.each([
    [1.5, 1],
    [Infinity, 1],
    [-0.5, 0],
    [-Infinity, 0]
  ])('clamps %d to %d', (level, expected) => {
    const player = new VolumeTestPlayer(0.5)
    player.setVolume(level)
    expect(player.getVolume()).toBe(expected)
  })

  it('leaves the volume as it is for NaN', () => {
    const player = new VolumeTestPlayer(0.5)
    player.setVolume(NaN)
    expect(player.getVolume()).toBe(0.5)
  })
})
