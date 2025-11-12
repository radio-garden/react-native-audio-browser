import Icon from '@react-native-vector-icons/fontawesome6'
import React from 'react'
import { Image, StyleSheet, Text, TouchableOpacity, View } from 'react-native'
import { Track } from 'react-native-audio-browser'

type TrackListItemProps = {
  track: Track
  isActive?: boolean
  onPress: () => void
}

export function TrackListItem({
  track,
  isActive,
  onPress
}: TrackListItemProps) {
  return (
    <TouchableOpacity
      style={[styles.item, isActive && styles.activeItem]}
      onPress={onPress}
    >
      <View style={styles.itemContent}>
        <Text style={[styles.itemTitle, isActive && styles.activeItemTitle]}>
          {track.title}
        </Text>
        {track.subtitle && (
          <Text
            style={[styles.itemSubtitle, isActive && styles.activeItemSubtitle]}
          >
            {track.subtitle}
          </Text>
        )}
        {track.artist && (
          <Text
            style={[styles.itemArtist, isActive && styles.activeItemArtist]}
          >
            {track.artist}
          </Text>
        )}
      </View>
      {track.src ? (
        track.artworkSource ? (
          <Image source={track.artworkSource} style={styles.itemArtwork} />
        ) : (
          <Icon name="music" size={16} color="#ffffff" iconStyle="solid" />
        )
      ) : (
        <Icon
          name="chevron-right"
          size={14}
          color="#ffffff"
          iconStyle="solid"
        />
      )}
    </TouchableOpacity>
  )
}

const styles = StyleSheet.create({
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#222222'
  },
  itemContent: {
    flex: 1
  },
  itemTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 2
  },
  itemSubtitle: {
    fontSize: 14,
    color: '#888888',
    marginBottom: 2
  },
  itemArtist: {
    fontSize: 14,
    color: '#666666'
  },
  itemArtwork: {
    width: 48,
    height: 48,
    borderRadius: 4,
    marginLeft: 12
  },
  activeItem: {
    backgroundColor: '#1a1a1a'
  },
  activeItemTitle: {
    color: '#007AFF'
  },
  activeItemSubtitle: {
    color: '#aaaaaa'
  },
  activeItemArtist: {
    color: '#888888'
  }
})
