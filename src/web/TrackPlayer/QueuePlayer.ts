import type {
  PlaybackState,
  RepeatMode as RepeatModeType
} from '../../features'
import type { Track } from '../../types'
import { Player } from './Player'
import { QueueManager } from './QueueManager'
import { RepeatMode } from './RepeatMode'
import { State } from './State'

/**
 * A {@link Player} with a play queue. Owns the *orchestration* — advancing the
 * queue on track end, loading the track when the index changes — while the
 * queue data and index math live in {@link QueueManager}.
 */
export class QueuePlayer extends Player {
  protected queue = new QueueManager()

  protected applyState(state: PlaybackState) {
    super.applyState(state)

    // dispatch() already gates on _isStopped before reaching applyState, so a
    // natural end while stopped never advances the queue.
    if (state === State.Ended) {
      this.onTrackEnded()
    }
  }

  protected onTrackEnded() {
    switch (this.queue.repeatMode) {
      case RepeatMode.Track:
        if (this.queue.currentIndex !== undefined) {
          this.goToIndex(this.queue.currentIndex)
        }
        break
      case RepeatMode.Queue:
        this.skipToNext()
        break
      default:
        if (this.queue.nextIndex() !== undefined) {
          this.skipToNext()
        } else {
          this.onQueueEnded()
        }
        break
    }
  }

  protected onQueueEnded() {
    console.warn('`onQueueEnded` is currently unimplemented')
  }

  protected goToIndex(index: number, initialPosition?: number) {
    const track = this.queue.getTrack(index)

    if (!track) return

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const onLoaded = (_track: Track) => {
      if (initialPosition !== undefined) {
        this.seekTo(initialPosition)
      }
    }

    if (this.queue.currentIndex !== index) {
      this.queue.currentIndex = index
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
    this.queue.insert(tracks, insertBeforeIndex)

    if (this.queue.currentIndex === undefined) {
      this.goToIndex(0)
    }

    if (this.queue.shuffleEnabled) {
      this.queue.regenerateShuffleOrder()
    }
  }

  public skip(index: number, initialPosition?: number): void {
    if (this.queue.getTrack(index) === undefined) {
      throw new Error('index out of bounds')
    }

    this.goToIndex(index, initialPosition)
  }

  public skipToNext(initialPosition?: number): void {
    if (this.queue.currentIndex === undefined) return

    const nextIndex = this.queue.nextIndex() ?? this.queue.wrapAroundFirstIndex()
    if (nextIndex === undefined) return

    this.goToIndex(nextIndex, initialPosition)
  }

  public skipToPrevious(initialPosition?: number): void {
    if (this.queue.currentIndex === undefined) return

    const previousIndex =
      this.queue.previousIndex() ?? this.queue.wrapAroundLastIndex()
    if (previousIndex === undefined) return

    this.goToIndex(previousIndex, initialPosition)
  }

  public setShuffleEnabled(enabled: boolean): void {
    this.queue.setShuffleEnabled(enabled)
  }

  public getShuffleEnabled(): boolean {
    return this.queue.shuffleEnabled
  }

  public getTrack(index: number): Track | undefined {
    return this.queue.getTrack(index)
  }

  public setRepeatMode(mode: RepeatModeType): void {
    this.queue.repeatMode = mode
  }

  public getRepeatMode(): RepeatModeType {
    return this.queue.repeatMode
  }

  public remove(indexes: number[]): void {
    const outcome = this.queue.remove(indexes)
    switch (outcome.kind) {
      case 'no-current':
        return
      case 'kept':
        break
      case 'reload':
        this.goToIndex(outcome.index)
        break
      case 'emptied':
        this.current = undefined
        this.stop()
        break
    }

    if (this.queue.shuffleEnabled) {
      this.queue.regenerateShuffleOrder()
    }
  }

  public stop(onComplete?: () => void): void {
    super.stop(onComplete)
  }

  public reset(): void {
    // Clear queue state synchronously so subsequent add()/load() calls
    // see a clean slate. The async player.unload() in stop() can finish
    // in the background — it only releases the Shaka source.
    this.queue.clear()
    this.current = undefined
    this.stop()
  }

  public removeUpcomingTracks(): void {
    this.queue.removeUpcoming()
  }

  public move(fromIndex: number, toIndex: number): void {
    this.queue.move(fromIndex, toIndex)
  }
}
