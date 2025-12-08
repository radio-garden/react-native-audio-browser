import Icon from '@react-native-vector-icons/fontawesome6'
import React from 'react'
import {
  ActivityIndicator,
  Image,
  StyleSheet,
  Text,
  TouchableOpacity,
  View
} from 'react-native'
import {
  skipToNext,
  skipToPrevious,
  toggleActiveTrackFavorited,
  togglePlayback,
  useActiveTrack,
  useEqualizerSettings,
  useNowPlaying,
  usePlaybackError,
  usePlayingState,
  useSleepTimerActive
} from 'react-native-audio-browser'

type MiniPlayerProps = {
  onEqualizerPress: () => void
  onSleepTimerPress: () => void
}

export function MiniPlayer({
  onEqualizerPress,
  onSleepTimerPress
}: MiniPlayerProps) {
  const track = useActiveTrack()
  const nowPlaying = useNowPlaying()
  const playingState = usePlayingState()
  const playbackError = usePlaybackError()
  const sleepTimerActive = useSleepTimerActive()
  const equalizerSettings = useEqualizerSettings()

  if (!nowPlaying || !track) return null

  const { title, artist, artwork } = nowPlaying

  return (
    <View style={styles.container}>
      <View style={styles.info}>
        {artwork ? (
          <Image source={{ uri: artwork }} style={styles.artwork} />
        ) : (
          <View style={styles.artworkPlaceholder}>
            <Text style={styles.artworkEmoji}>🎵</Text>
          </View>
        )}
        <View style={styles.text}>
          <Text style={styles.title} numberOfLines={1}>
            {title}
          </Text>
          <Text
            style={[styles.artist, playbackError && styles.error]}
            numberOfLines={1}
          >
            {playbackError?.message ?? artist}
          </Text>
        </View>
      </View>
      <View style={styles.controls}>
        <TouchableOpacity
          style={styles.controlButton}
          onPress={toggleActiveTrackFavorited}
        >
          <Icon
            name="heart"
            size={20}
            color="white"
            iconStyle={track.favorited ? 'solid' : 'regular'}
          />
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.controlButton}
          onPress={() => skipToPrevious()}
        >
          <Icon
            name="backward-step"
            size={20}
            color="white"
            iconStyle="solid"
          />
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.controlButton, styles.playPauseButton]}
          onPress={togglePlayback}
        >
          {playingState.buffering ? (
            <ActivityIndicator size="small" color="white" />
          ) : (
            <Icon
              name={playingState.playing ? 'pause' : 'play'}
              size={20}
              color="white"
              iconStyle="solid"
            />
          )}
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.controlButton}
          onPress={() => skipToNext()}
        >
          <Icon
            name="forward-step"
            size={20}
            color="white"
            iconStyle="solid"
          />
        </TouchableOpacity>
        {equalizerSettings != null && (
          <TouchableOpacity
            style={styles.controlButton}
            onPress={onEqualizerPress}
          >
            <View style={{ transform: [{ rotate: '90deg' }] }}>
              <Icon
                name="sliders"
                size={20}
                color={equalizerSettings.enabled ? '#007AFF' : 'white'}
                iconStyle="solid"
              />
            </View>
          </TouchableOpacity>
        )}
        <TouchableOpacity
          style={styles.controlButton}
          onPress={onSleepTimerPress}
        >
          <Icon
            name="moon"
            size={20}
            color={sleepTimerActive ? '#007AFF' : 'white'}
            iconStyle="solid"
          />
        </TouchableOpacity>
      </View>
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#252525',
    padding: 12
  },
  info: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    marginRight: 12
  },
  artwork: {
    width: 48,
    height: 48,
    borderRadius: 4,
    marginRight: 12
  },
  artworkPlaceholder: {
    width: 48,
    height: 48,
    borderRadius: 4,
    backgroundColor: '#333333',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12
  },
  artworkEmoji: {
    fontSize: 24
  },
  text: {
    flex: 1
  },
  title: {
    fontSize: 14,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 2
  },
  artist: {
    fontSize: 12,
    color: '#888888'
  },
  error: {
    color: '#ff6b6b'
  },
  controls: {
    flexDirection: 'row',
    alignItems: 'center'
  },
  controlButton: {
    padding: 8,
    marginLeft: 4
  },
  playPauseButton: {
    width: 36,
    alignItems: 'center'
  }
})
