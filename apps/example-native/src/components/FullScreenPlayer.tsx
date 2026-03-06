import type { RepeatMode } from 'react-native-audio-browser'
import Icon from '@react-native-vector-icons/fontawesome6'
import React, { useRef, useState } from 'react'
import {
  ActivityIndicator,
  Image,
  Modal,
  PanResponder,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View
} from 'react-native'
import { DebugPanel } from './DebugPanel'
import { EqualizerModal } from './EqualizerModal'
import { SleepTimerModal } from './SleepTimerModal'
import {
  getRate,
  getRepeatMode,
  openIosOutputPicker,
  seekTo,
  setRate,
  setRepeatMode,
  skipToNext,
  skipToPrevious,
  toggleActiveTrackFavorited,
  togglePlayback,
  toggleShuffle,
  useActiveTrack,
  useEqualizerSettings,
  useIosOutput,
  useNowPlaying,
  usePlayingState,
  usePolledProgress,
  useRepeatMode,
  useShuffle,
  useSleepTimerActive
} from 'react-native-audio-browser'

type Props = {
  visible: boolean
  onClose: () => void
}

const RATE_OPTIONS = [0.5, 1, 1.5, 2]

function cycleRepeatMode() {
  const modes: RepeatMode[] = ['off', 'track', 'queue']
  const currentIndex = modes.indexOf(getRepeatMode())
  const nextIndex = (currentIndex + 1) % modes.length
  setRepeatMode(modes[nextIndex])
}

function formatTime(seconds: number): string {
  const mins = Math.floor(seconds / 60)
  const secs = Math.floor(seconds % 60)
  return `${mins}:${secs.toString().padStart(2, '0')}`
}

export function FullScreenPlayer({ visible, onClose }: Props) {
  const [showEqualizer, setShowEqualizer] = useState(false)
  const [showSleepTimer, setShowSleepTimer] = useState(false)
  const track = useActiveTrack()
  const nowPlaying = useNowPlaying()
  const playingState = usePlayingState()
  const progress = usePolledProgress()
  const shuffleEnabled = useShuffle()
  const repeatMode = useRepeatMode()
  const sleepTimerActive = useSleepTimerActive()
  const equalizerSettings = useEqualizerSettings()
  const iosOutput = useIosOutput()
  const { width: screenWidth } = useWindowDimensions()
  const [rateState, setRateState] = useState(() => getRate())

  const artworkSize = Math.min(screenWidth - 64, 300)

  function cycleRate() {
    const currentIndex = RATE_OPTIONS.indexOf(rateState)
    const nextIndex = (currentIndex + 1) % RATE_OPTIONS.length
    const nextRate = RATE_OPTIONS[nextIndex]
    setRate(nextRate)
    setRateState(nextRate)
  }

  const { title, artist, artwork } = nowPlaying ?? {}
  const sliderValue =
    progress.duration > 0 ? progress.position / progress.duration : 0

  return (
    <Modal
      visible={visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={onClose}
    >
      <View style={styles.modalContainer}>
        {/* Header */}
        <View style={styles.header}>
          <Pressable style={({ pressed }) => ({ opacity: pressed ? 0.5 : 1 })} onPress={onClose}>
            <Text style={styles.closeButtonText}>Done</Text>
          </Pressable>
        </View>

        <View style={styles.content}>
          {/* Artwork */}
          <View style={styles.artworkContainer}>
            {artwork ? (
              <Image
                source={{ uri: artwork }}
                style={[
                  styles.artwork,
                  { width: artworkSize, height: artworkSize }
                ]}
              />
            ) : (
              <View
                style={[
                  styles.artwork,
                  styles.artworkPlaceholder,
                  { width: artworkSize, height: artworkSize }
                ]}
              >
                <Text style={styles.artworkEmoji}>🎵</Text>
              </View>
            )}
          </View>

          {/* Track Info */}
          <View style={styles.trackInfo}>
            <Text style={styles.title} numberOfLines={2}>
              {title}
            </Text>
            <Text style={styles.artist} numberOfLines={1}>
              {artist}
            </Text>
          </View>

          {/* Scrubber */}
          <View style={styles.progressContainer}>
            <Scrubber
              value={sliderValue}
              duration={progress.duration}
              onSeek={seekTo}
            />
            <View style={styles.progressTimes}>
              <Text style={styles.progressTimeText}>
                {formatTime(progress.position)}
              </Text>
              <Text style={styles.progressTimeText}>
                {progress.duration > 0
                  ? `-${formatTime(progress.duration - progress.position)}`
                  : '--:--'}
              </Text>
            </View>
          </View>

          {/* Primary Controls */}
          <View style={styles.primaryControls}>
            <Pressable
              style={({ pressed }) => [styles.primaryButton, { opacity: pressed ? 0.5 : 1 }]}
              onPress={() => skipToPrevious()}
            >
              <Icon
                name="backward-step"
                size={32}
                color="white"
                iconStyle="solid"
              />
            </Pressable>
            <Pressable
              style={({ pressed }) => [styles.playPauseButton, { opacity: pressed ? 0.5 : 1 }]}
              onPress={togglePlayback}
            >
              {playingState.buffering ? (
                <ActivityIndicator size="large" color="white" />
              ) : (
                <Icon
                  name={playingState.playing ? 'pause' : 'play'}
                  size={36}
                  color="white"
                  iconStyle="solid"
                />
              )}
            </Pressable>
            <Pressable
              style={({ pressed }) => [styles.primaryButton, { opacity: pressed ? 0.5 : 1 }]}
              onPress={() => skipToNext()}
            >
              <Icon
                name="forward-step"
                size={32}
                color="white"
                iconStyle="solid"
              />
            </Pressable>
          </View>

          {/* Secondary Controls */}
          <View style={styles.secondaryControls}>
            {track && (
              <Pressable
                style={({ pressed }) => [styles.secondaryButton, { opacity: pressed ? 0.5 : 1 }]}
                onPress={toggleActiveTrackFavorited}
              >
                <Icon
                  name="heart"
                  size={20}
                  color="white"
                  iconStyle={track.favorited ? 'solid' : 'regular'}
                />
              </Pressable>
            )}
            <Pressable
              style={({ pressed }) => [styles.secondaryButton, { opacity: pressed ? 0.5 : 1 }]}
              onPress={toggleShuffle}
            >
              <Icon
                name="shuffle"
                size={20}
                color={shuffleEnabled ? '#007AFF' : 'white'}
                iconStyle="solid"
              />
            </Pressable>
            <Pressable
              style={({ pressed }) => [styles.secondaryButton, { opacity: pressed ? 0.5 : 1 }]}
              onPress={cycleRepeatMode}
            >
              <Icon
                name={repeatMode === 'track' ? '1' : 'repeat'}
                size={20}
                color={repeatMode === 'off' ? 'white' : '#007AFF'}
                iconStyle="solid"
              />
            </Pressable>
            <Pressable
              style={({ pressed }) => [styles.secondaryButton, { opacity: pressed ? 0.5 : 1 }]}
              onPress={cycleRate}
            >
              <Text
                style={[
                  styles.rateLabel,
                  rateState !== 1 && styles.rateLabelActive
                ]}
              >
                {rateState}x
              </Text>
            </Pressable>
            <Pressable
              style={({ pressed }) => [styles.secondaryButton, { opacity: pressed ? 0.5 : 1 }]}
              onPress={() => setShowSleepTimer(true)}
            >
              <Icon
                name="moon"
                size={20}
                color={sleepTimerActive ? '#007AFF' : 'white'}
                iconStyle="solid"
              />
            </Pressable>
            {equalizerSettings != null && (
              <Pressable
                style={({ pressed }) => [styles.secondaryButton, { opacity: pressed ? 0.5 : 1 }]}
                onPress={() => setShowEqualizer(true)}
              >
                <View style={{ transform: [{ rotate: '90deg' }] }}>
                  <Icon
                    name="sliders"
                    size={20}
                    color={equalizerSettings.enabled ? '#007AFF' : 'white'}
                    iconStyle="solid"
                  />
                </View>
              </Pressable>
            )}
            {Platform.OS === 'ios' && (
              <Pressable
                style={({ pressed }) => [styles.secondaryButton, { opacity: pressed ? 0.5 : 1 }]}
                onPress={openIosOutputPicker}
              >
                <Icon
                  name="headphones"
                  size={20}
                  color={iosOutput?.external ? '#007AFF' : 'white'}
                  iconStyle="solid"
                />
              </Pressable>
            )}
          </View>
        </View>

        <EqualizerModal
          visible={showEqualizer}
          onClose={() => setShowEqualizer(false)}
        />
        <SleepTimerModal
          visible={showSleepTimer}
          onClose={() => setShowSleepTimer(false)}
        />

        {__DEV__ && <DebugPanel />}
      </View>
    </Modal>
  )
}

function Scrubber({
  value,
  duration,
  onSeek
}: {
  value: number
  duration: number
  onSeek: (position: number) => void
}) {
  const barWidth = useRef(0)
  const barX = useRef(0)
  const viewRef = useRef<View>(null)
  const durationRef = useRef(duration)
  const onSeekRef = useRef(onSeek)
  durationRef.current = duration
  onSeekRef.current = onSeek
  const [scrubState, setScrubState] = useState<
    | { active: false }
    | { active: true; dragging: true; ratio: number }
    | { active: true; dragging: false; ratio: number }
  >({ active: false })

  const clampRatio = (x: number) =>
    Math.max(0, Math.min(1, barWidth.current > 0 ? x / barWidth.current : 0))

  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (e) => {
        const ratio = clampRatio(e.nativeEvent.pageX - barX.current)
        setScrubState({ active: true, dragging: true, ratio })
      },
      onPanResponderMove: (e) => {
        const ratio = clampRatio(e.nativeEvent.pageX - barX.current)
        setScrubState({ active: true, dragging: true, ratio })
      },
      onPanResponderRelease: (e) => {
        const ratio = clampRatio(e.nativeEvent.pageX - barX.current)
        setScrubState({ active: true, dragging: false, ratio })
        if (durationRef.current > 0) {
          onSeekRef.current(ratio * durationRef.current)
        }
      },
      onPanResponderTerminate: () => {
        setScrubState({ active: false })
      }
    })
  ).current

  // Clear optimistic hold once polled progress catches up
  if (
    scrubState.active &&
    !scrubState.dragging &&
    Math.abs(value - scrubState.ratio) < 0.02
  ) {
    setScrubState({ active: false })
  }

  const displayValue = scrubState.active ? scrubState.ratio : value

  return (
    <View
      ref={viewRef}
      style={styles.scrubberOuter}
      onLayout={(e) => {
        barWidth.current = e.nativeEvent.layout.width
        viewRef.current?.measureInWindow((x) => {
          barX.current = x
        })
      }}
      {...panResponder.panHandlers}
    >
      <View style={styles.scrubberTrack}>
        <View
          style={[
            styles.scrubberFill,
            { width: `${displayValue * 100}%` }
          ]}
        />
      </View>
      <View
        style={[
          styles.scrubberThumb,
          { left: `${displayValue * 100}%` }
        ]}
      />
    </View>
  )
}

const styles = StyleSheet.create({
  modalContainer: {
    flex: 1,
    backgroundColor: '#000000'
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 8
  },
  closeButtonText: {
    color: '#007AFF',
    fontSize: 16,
    fontWeight: '600'
  },
  content: {
    flex: 1,
    paddingHorizontal: 32,
    paddingBottom: 32,
    justifyContent: 'space-evenly'
  },
  artworkContainer: {
    alignItems: 'center'
  },
  artwork: {
    borderRadius: 8
  },
  artworkPlaceholder: {
    backgroundColor: '#333333',
    justifyContent: 'center',
    alignItems: 'center'
  },
  artworkEmoji: {
    fontSize: 64
  },
  trackInfo: {
    alignItems: 'center'
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    color: '#ffffff',
    textAlign: 'center',
    marginBottom: 4
  },
  artist: {
    fontSize: 16,
    color: '#888888',
    textAlign: 'center'
  },
  progressContainer: {},
  scrubberOuter: {
    paddingVertical: 12,
    justifyContent: 'center'
  },
  scrubberTrack: {
    height: 4,
    backgroundColor: '#333333',
    borderRadius: 2,
    overflow: 'hidden'
  },
  scrubberFill: {
    height: '100%',
    backgroundColor: '#007AFF',
    borderRadius: 2
  },
  scrubberThumb: {
    position: 'absolute',
    width: 16,
    height: 16,
    borderRadius: 8,
    backgroundColor: '#ffffff',
    marginLeft: -8,
    top: 6
  },
  progressTimes: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 4
  },
  progressTimeText: {
    fontSize: 12,
    color: '#888888'
  },
  primaryControls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 40
  },
  primaryButton: {
    padding: 12
  },
  playPauseButton: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: '#333333',
    justifyContent: 'center',
    alignItems: 'center'
  },
  secondaryControls: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 8
  },
  secondaryButton: {
    padding: 10
  },
  rateLabel: {
    fontSize: 13,
    fontWeight: '700',
    color: 'white'
  },
  rateLabelActive: {
    color: '#007AFF'
  }
})
