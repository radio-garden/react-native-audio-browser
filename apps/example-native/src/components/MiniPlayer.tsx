import Icon from '@react-native-vector-icons/fontawesome6'
import React from 'react'
import {
  ActivityIndicator,
  Image,
  Pressable,
  StyleSheet,
  Text,
  View
} from 'react-native'
import {
  skipToNext,
  skipToPrevious,
  togglePlayback,
  useActiveTrack,
  useNowPlaying,
  usePlaybackError,
  usePlayingState
} from 'react-native-audio-browser'

type MiniPlayerProps = {
  onPress: () => void
}

export function MiniPlayer({ onPress }: MiniPlayerProps) {
  const track = useActiveTrack()
  const nowPlaying = useNowPlaying()
  const playingState = usePlayingState()
  const playbackError = usePlaybackError()

  if (!nowPlaying || !track) return null

  const { title, artist, artwork } = nowPlaying

  return (
    <View style={styles.container}>
      <Pressable style={({ pressed }) => [styles.info, { opacity: pressed ? 0.5 : 1 }]} onPress={onPress}>
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
      </Pressable>
      <View style={styles.controls}>
        <Pressable
          style={({ pressed }) => [styles.controlButton, { opacity: pressed ? 0.5 : 1 }]}
          onPress={() => skipToPrevious()}
        >
          <Icon
            name="backward-step"
            size={20}
            color="white"
            iconStyle="solid"
          />
        </Pressable>
        <Pressable
          style={({ pressed }) => [styles.controlButton, styles.playPauseButton, { opacity: pressed ? 0.5 : 1 }]}
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
        </Pressable>
        <Pressable
          style={({ pressed }) => [styles.controlButton, { opacity: pressed ? 0.5 : 1 }]}
          onPress={() => skipToNext()}
        >
          <Icon
            name="forward-step"
            size={20}
            color="white"
            iconStyle="solid"
          />
        </Pressable>
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
