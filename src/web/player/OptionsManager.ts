import type {
  NativeUpdateOptions,
  UpdateOptions,
  Options,
  RepeatMode as RepeatModeType
} from '../../features'
import { RepeatMode } from '../TrackPlayer'

type InternalOptions = UpdateOptions & {
  repeatMode: RepeatModeType
}

/**
 * Manages player options and configuration.
 * Handles option updates and change detection.
 */
export class OptionsManager {
  private options: InternalOptions = {
    forwardJumpInterval: 15,
    backwardJumpInterval: 15,
    progressUpdateEventInterval: null,
    repeatMode: RepeatMode.Off,
    capabilities: {}
  }

  // Event callback
  onOptionsChanged: (options: Options) => void = () => {}

  /**
   * Updates player options with change detection.
   * Emits onOptionsChanged event after updating.
   *
   * @param options Partial options to update
   */
  updateOptions(options: NativeUpdateOptions): void {
    if (
      options.progressUpdateEventInterval !== null &&
      options.progressUpdateEventInterval !== undefined &&
      typeof options.progressUpdateEventInterval !== 'number'
    ) {
      throw new Error('NullSentinal type is not valid on web.')
    }

    // Merge platform-agnostic options
    const mergedOptions: InternalOptions = {
      ...this.options,
      forwardJumpInterval:
        options.forwardJumpInterval ?? this.options.forwardJumpInterval,
      backwardJumpInterval:
        options.backwardJumpInterval ?? this.options.backwardJumpInterval,
      capabilities: options.capabilities ?? this.options.capabilities,
      progressUpdateEventInterval:
        options.progressUpdateEventInterval === null
          ? null
          : (options.progressUpdateEventInterval ??
            this.options.progressUpdateEventInterval)
    }

    // Store merged options
    this.options = mergedOptions

    // Call callback with full Options type (including repeatMode)
    const fullOptions: Options = {
      forwardJumpInterval: mergedOptions.forwardJumpInterval ?? 15,
      backwardJumpInterval: mergedOptions.backwardJumpInterval ?? 15,
      progressUpdateEventInterval:
        mergedOptions.progressUpdateEventInterval ?? 15,
      capabilities: mergedOptions.capabilities ?? {},
      repeatMode: mergedOptions.repeatMode
    }
    this.onOptionsChanged(fullOptions)
  }

  /**
   * Gets current player options (excluding repeatMode).
   */
  getOptions(): UpdateOptions {
    return {
      forwardJumpInterval: this.options.forwardJumpInterval,
      backwardJumpInterval: this.options.backwardJumpInterval,
      progressUpdateEventInterval: this.options.progressUpdateEventInterval,
      capabilities: this.options.capabilities
    }
  }

  /**
   * Gets the current repeat mode.
   */
  getRepeatMode(): RepeatModeType {
    return this.options.repeatMode
  }

  /**
   * Sets the repeat mode.
   * Updates internal state but does not emit events - caller should handle that.
   *
   * @param mode New repeat mode
   */
  setRepeatMode(mode: RepeatModeType): void {
    this.options.repeatMode = mode
  }
}
