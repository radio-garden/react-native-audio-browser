import { Player } from './Player'
import { State } from './State'
import type { State as StateType } from './State'

import type { Track } from '../../types'
import type { RepeatMode as RepeatModeType } from '../../features'
import { RepeatMode } from './RepeatMode'
import { fisherYatesShuffle } from '../util/shuffle'

export class PlaylistPlayer extends Player {
  // TODO: use immer to make the `playlist` immutable
  protected playlist: Track[] = []
  protected lastIndex?: number
  protected _currentIndex?: number
  protected repeatMode: RepeatModeType = RepeatMode.Off
  protected shuffleEnabled: boolean = false
  protected shuffleOrder: number[] = []

  protected onStateUpdate(state: Exclude<StateType, typeof State.Error>) {
    super.onStateUpdate(state)

    if (state === State.Ended) {
      this.onTrackEnded()
    }
  }

  protected onTrackEnded() {
    switch (this.repeatMode) {
      case RepeatMode.Track:
        if (this.currentIndex !== undefined) {
          this.goToIndex(this.currentIndex)
        }
        break
      case RepeatMode.Playlist:
        if (this.currentIndex === this.playlist.length - 1) {
          this.goToIndex(0)
        } else {
          this.skipToNext()
        }
        break
      default:
        try {
          this.skipToNext()
        } catch (err) {
          if ((err as Error).message !== 'playlist_exhausted') {
            throw err
          }
          this.onPlaylistEnded()
        }
        break
    }
  }

  protected onPlaylistEnded() {
    console.warn('`onPlaylistEnded` is currently unimplemented')
  }

  protected get currentIndex() {
    return this._currentIndex
  }

  protected set currentIndex(current: number | undefined) {
    this.lastIndex = this.currentIndex
    this._currentIndex = current
  }

  protected goToIndex(index: number, initialPosition?: number) {
    const track = this.playlist[index]

    if (!track) {
      throw new Error('playlist_exhausted')
    }

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const onCompletedLoading = (_track: Track) => {
      if (initialPosition !== undefined) {
        this.seekTo(initialPosition)
      }

      if (this.playWhenReady) {
        this.play()
      }
    }

    if (this.currentIndex !== index) {
      this.currentIndex = index
      this.load(track, onCompletedLoading)
    } else {
      // Replay the same track - seek to start (or initialPosition if specified)
      this.seekTo(initialPosition ?? 0)
      if (this.playWhenReady) {
        this.play()
      }
    }
  }

  public add(tracks: Track[], insertBeforeIndex?: number): void {
    if (insertBeforeIndex !== -1 && insertBeforeIndex !== undefined) {
      this.playlist.splice(insertBeforeIndex, 0, ...tracks)
    } else {
      this.playlist.push(...tracks)
    }

    if (this.currentIndex === undefined) {
      this.goToIndex(0)
    }

    // Regenerate shuffle order when tracks are added
    if (this.shuffleEnabled) {
      this.generateShuffleOrder()
    }
  }

  public skip(index: number, initialPosition?: number): void {
    const track = this.playlist[index]

    if (track === undefined) {
      throw new Error('index out of bounds')
    }

    this.goToIndex(index, initialPosition)
  }

  public skipToNext(initialPosition?: number): void {
    if (this.currentIndex === undefined) return

    const nextIndex = this.getNextIndex()
    if (nextIndex !== undefined) {
      this.goToIndex(nextIndex, initialPosition)
    }
  }

  public skipToPrevious(initialPosition?: number): void {
    if (this.currentIndex === undefined) return

    const previousIndex = this.getPreviousIndex()
    if (previousIndex !== undefined) {
      this.goToIndex(previousIndex, initialPosition)
    }
  }

  protected getNextIndex(): number | undefined {
    if (this.currentIndex === undefined) return undefined

    if (this.shuffleEnabled) {
      // Find current position in shuffle order
      const currentShufflePos = this.shuffleOrder.indexOf(this.currentIndex)
      if (currentShufflePos === -1) return undefined

      // Get next position in shuffle order
      const nextShufflePos = currentShufflePos + 1
      if (nextShufflePos >= this.shuffleOrder.length) {
        throw new Error('playlist_exhausted')
      }

      return this.shuffleOrder[nextShufflePos]
    } else {
      // Normal sequential order
      return this.currentIndex + 1
    }
  }

  protected getPreviousIndex(): number | undefined {
    if (this.currentIndex === undefined) return undefined

    if (this.shuffleEnabled) {
      // Find current position in shuffle order
      const currentShufflePos = this.shuffleOrder.indexOf(this.currentIndex)
      if (currentShufflePos === -1) return undefined

      // Get previous position in shuffle order
      const previousShufflePos = currentShufflePos - 1
      if (previousShufflePos < 0) {
        throw new Error('playlist_exhausted')
      }

      return this.shuffleOrder[previousShufflePos]
    } else {
      // Normal sequential order
      return this.currentIndex - 1
    }
  }

  protected generateShuffleOrder(): void {
    // Create array of indices [0, 1, 2, ..., n-1]
    this.shuffleOrder = Array.from({ length: this.playlist.length }, (_, i) => i)

    // Shuffle the indices
    fisherYatesShuffle(this.shuffleOrder)

    // If there's a current track, move it to the beginning of the shuffle order
    if (this.currentIndex !== undefined) {
      const currentPos = this.shuffleOrder.indexOf(this.currentIndex)
      if (currentPos > 0) {
        const temp = this.shuffleOrder[0]!
        this.shuffleOrder[0] = this.shuffleOrder[currentPos]!
        this.shuffleOrder[currentPos] = temp
      }
    }
  }

  public setShuffleEnabled(enabled: boolean): void {
    this.shuffleEnabled = enabled

    if (enabled) {
      // Generate new shuffle order when enabling shuffle
      this.generateShuffleOrder()
    }
  }

  public getShuffleEnabled(): boolean {
    return this.shuffleEnabled
  }

  public getTrack(index: number): Track | undefined {
    const track = this.playlist[index]
    return track
  }

  public setRepeatMode(mode: RepeatModeType): void {
    this.repeatMode = mode
  }

  public getRepeatMode(): RepeatModeType {
    return this.repeatMode
  }

  public remove(indexes: number[]): void {
    const idxMap = indexes.reduce<Record<number, boolean>>((acc, elem) => {
      acc[elem] = true
      return acc
    }, {})
    let isCurrentRemoved = false
    this.playlist = this.playlist.filter((_track, idx) => {
      const keep = !idxMap[idx]

      if (!keep && idx === this.currentIndex) {
        isCurrentRemoved = true
      }

      return keep
    })

    if (this.currentIndex === undefined) {
      return
    }

    const hasItems = this.playlist.length > 0
    if (isCurrentRemoved && hasItems) {
      this.goToIndex(this.currentIndex % this.playlist.length)
    } else if (isCurrentRemoved) {
      this.stop()
    }

    // Regenerate shuffle order when tracks are removed
    if (this.shuffleEnabled) {
      this.generateShuffleOrder()
    }
  }

  public stop(onComplete?: () => void): void {
    super.stop(() => {
      this.currentIndex = undefined
      onComplete?.()
    })
  }

  public reset(): void {
    this.stop(() => {
      this.playlist = []
    })
  }

  public removeUpcomingTracks(): void {
    if (this.currentIndex === undefined) return
    this.playlist = this.playlist.slice(0, this.currentIndex + 1)

    // Regenerate shuffle order when tracks are removed
    if (this.shuffleEnabled) {
      this.generateShuffleOrder()
    }
  }

  public move(fromIndex: number, toIndex: number): void {
    if (!this.playlist[fromIndex]) {
      throw new Error('index out of bounds')
    }

    if (this.currentIndex === fromIndex) {
      throw new Error('you cannot move the currently playing track')
    }

    if (this.currentIndex === toIndex) {
      throw new Error('you cannot replace the currently playing track')
    }

    // calculate `currentIndex` after move
    let shift: number | undefined
    if (
      this.currentIndex !== undefined &&
      fromIndex < this.currentIndex &&
      toIndex > this.currentIndex
    ) {
      shift = -1
    } else if (
      this.currentIndex !== undefined &&
      fromIndex > this.currentIndex &&
      toIndex < this.currentIndex
    ) {
      shift = 1
    }

    // move the track
    const fromItem = this.playlist[fromIndex]
    this.playlist.splice(fromIndex, 1)
    this.playlist.splice(toIndex, 0, fromItem)

    if (this.currentIndex !== undefined && shift) {
      this.currentIndex = this.currentIndex + shift
    }

    // Regenerate shuffle order when tracks are moved
    if (this.shuffleEnabled) {
      this.generateShuffleOrder()
    }
  }
}
