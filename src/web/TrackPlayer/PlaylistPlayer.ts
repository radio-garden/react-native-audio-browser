import type { RepeatMode as RepeatModeType } from '../../features'
import type { Track } from '../../types'
import type { State as StateType } from './State'
import { assertedNotNullish } from '../../utils/validation'
import { fisherYatesShuffle } from '../util/shuffle'
import { Player } from './Player'
import { RepeatMode } from './RepeatMode'
import { State } from './State'

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

    if (this._isStopped) return

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
        this.skipToNext()
        break
      default:
        if (this.getNextIndex() !== undefined) {
          this.skipToNext()
        } else {
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

    if (!track) return

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const onLoaded = (_track: Track) => {
      if (initialPosition !== undefined) {
        this.seekTo(initialPosition)
      }
    }

    if (this.currentIndex !== index) {
      this.currentIndex = index
      this.load(track, onLoaded)
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

    const nextIndex = this.getNextIndex() ?? this.getWrapAroundFirstIndex()
    if (nextIndex === undefined) return

    this.goToIndex(nextIndex, initialPosition)
  }

  public skipToPrevious(initialPosition?: number): void {
    if (this.currentIndex === undefined) return

    const previousIndex =
      this.getPreviousIndex() ?? this.getWrapAroundLastIndex()
    if (previousIndex === undefined) return

    this.goToIndex(previousIndex, initialPosition)
  }

  protected getNextIndex(): number | undefined {
    if (this.currentIndex === undefined) return undefined

    if (!this.shuffleEnabled) {
      // Normal sequential order
      const nextIndex = this.currentIndex + 1
      if (nextIndex >= this.playlist.length) return undefined
      return nextIndex
    }

    // Find current position in shuffle order
    const currentShufflePos = this.shuffleOrder.indexOf(this.currentIndex)
    if (currentShufflePos === -1) return undefined

    // Get next position in shuffle order
    const nextShufflePos = currentShufflePos + 1
    if (nextShufflePos >= this.shuffleOrder.length) {
      return undefined
    }

    return this.shuffleOrder[nextShufflePos]
  }

  protected getPreviousIndex(): number | undefined {
    if (this.currentIndex === undefined) return undefined

    if (!this.shuffleEnabled) {
      // Normal sequential order
      const prevIndex = this.currentIndex - 1
      if (prevIndex < 0) return undefined
      return prevIndex
    }

    // Find current position in shuffle order
    const currentShufflePos = this.shuffleOrder.indexOf(this.currentIndex)
    if (currentShufflePos === -1) return undefined

    // Get previous position in shuffle order
    const previousShufflePos = currentShufflePos - 1
    if (previousShufflePos < 0) {
      return undefined
    }

    return this.shuffleOrder[previousShufflePos]
  }

  protected getWrapAroundFirstIndex(): number | undefined {
    if (this.repeatMode !== RepeatMode.Playlist) return undefined
    if (this.shuffleEnabled) return this.shuffleOrder[0]
    return 0
  }

  protected getWrapAroundLastIndex(): number | undefined {
    if (this.repeatMode !== RepeatMode.Playlist) return undefined
    if (this.shuffleEnabled)
      return this.shuffleOrder[this.shuffleOrder.length - 1]
    return this.playlist.length - 1
  }

  protected generateShuffleOrder(): void {
    // Create array of indices [0, 1, 2, ..., n-1]
    this.shuffleOrder = Array.from(
      { length: this.playlist.length },
      (_, i) => i
    )

    // Shuffle the indices
    fisherYatesShuffle(this.shuffleOrder)

    // If there is NOT a current track, exit
    if (this.currentIndex === undefined) {
      return
    }

    // If there is a current track, move it to the beginning of the shuffle order
    const currentPos = this.shuffleOrder.indexOf(this.currentIndex)
    if (currentPos > 0) {
      const temp = assertedNotNullish(this.shuffleOrder[0])
      this.shuffleOrder[0] = assertedNotNullish(this.shuffleOrder[currentPos])
      this.shuffleOrder[currentPos] = temp
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
    const idxSet = new Set(indexes)
    let isCurrentRemoved = false
    let removedBeforeCurrent = 0

    this.playlist = this.playlist.filter((_track, idx) => {
      const keep = !idxSet.has(idx)

      if (!keep) {
        if (idx === this.currentIndex) {
          isCurrentRemoved = true
        } else if (this.currentIndex !== undefined && idx < this.currentIndex) {
          removedBeforeCurrent++
        }
      }

      return keep
    })

    if (this.currentIndex === undefined) {
      return
    }

    if (isCurrentRemoved) {
      const hasItems = this.playlist.length > 0
      if (hasItems) {
        // Adjust for removed items before current, then clamp to valid range
        const adjustedIndex = this.currentIndex - removedBeforeCurrent
        // Reset so goToIndex always loads the new track at this position
        this._currentIndex = undefined
        this.goToIndex(Math.min(adjustedIndex, this.playlist.length - 1))
      } else {
        this.current = undefined
        this._currentIndex = undefined
        this.stop()
      }
    } else {
      // Adjust currentIndex to account for removed items before it
      this._currentIndex = this.currentIndex - removedBeforeCurrent
    }

    // Regenerate shuffle order when tracks are removed
    if (this.shuffleEnabled) {
      this.generateShuffleOrder()
    }
  }

  public stop(onComplete?: () => void): void {
    super.stop(onComplete)
  }

  public reset(): void {
    // Clear queue state synchronously so subsequent add()/load() calls
    // see a clean slate. The async player.unload() in stop() can finish
    // in the background — it only releases the Shaka source.
    this.playlist = []
    this.current = undefined
    this._currentIndex = undefined
    this.stop()
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

    // Move the track in the playlist
    const fromItem = this.playlist[fromIndex]
    this.playlist.splice(fromIndex, 1)
    this.playlist.splice(toIndex, 0, fromItem)

    // Update currentIndex to track the currently playing item's new position.
    // Matches Android's exoPlayer.moveMediaItem() which has no restrictions.
    if (this.currentIndex !== undefined) {
      if (fromIndex === this.currentIndex) {
        // Moving the current track — follow it to its new position
        this._currentIndex = toIndex
      } else if (
        fromIndex < this.currentIndex &&
        toIndex >= this.currentIndex
      ) {
        this._currentIndex = this.currentIndex - 1
      } else if (
        fromIndex > this.currentIndex &&
        toIndex <= this.currentIndex
      ) {
        this._currentIndex = this.currentIndex + 1
      }
    }

    // Regenerate shuffle order when tracks are moved
    if (this.shuffleEnabled) {
      this.generateShuffleOrder()
    }
  }
}
