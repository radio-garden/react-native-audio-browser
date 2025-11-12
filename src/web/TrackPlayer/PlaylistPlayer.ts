import { Player } from './Player'
import { State } from './State'
import type { State as StateType } from './State'

import type { Track } from '../../types'
import type { RepeatMode as RepeatModeType } from '../../features'
import { RepeatMode } from './RepeatMode'

export class PlaylistPlayer extends Player {
  // TODO: use immer to make the `playlist` immutable
  protected playlist: Track[] = []
  protected lastIndex?: number
  protected _currentIndex?: number
  protected repeatMode: RepeatModeType = RepeatMode.Off

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

    const onCompletedLoading = (_track: Track) => {
      if (initialPosition) {
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
      onCompletedLoading(track)
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

    const index = this.currentIndex + 1
    this.goToIndex(index, initialPosition)
  }

  public skipToPrevious(initialPosition?: number): void {
    if (this.currentIndex === undefined) return

    const index = this.currentIndex - 1
    this.goToIndex(index, initialPosition)
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
    if (this.currentIndex) {
      if (fromIndex < this.currentIndex && toIndex > this.currentIndex) {
        shift = -1
      } else if (fromIndex > this.currentIndex && toIndex < this.currentIndex) {
        shift = 1
      }
    }

    // move the track
    const fromItem = this.playlist[fromIndex]
    this.playlist.splice(fromIndex, 1)
    this.playlist.splice(toIndex, 0, fromItem)

    if (this.currentIndex && shift) {
      this.currentIndex = this.currentIndex + shift
    }
  }

  // TODO
  public updateMetadataForTrack(
    _index: number,
    _metadata: Partial<Track>
  ): void {
    console.warn('`updateMetadataForTrack` is currently unimplemented')
  }

  public updateNowPlayingMetadata(_metadata: Partial<Track>): void {
    console.warn('`updateNowPlayingMetadata` is currently unimplemented')
  }
}
