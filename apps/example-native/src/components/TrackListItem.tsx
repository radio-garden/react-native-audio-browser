import Icon from '@react-native-vector-icons/fontawesome6'
import React from 'react'
import {
  Image,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View
} from 'react-native'
import { ImageRowItem, navigate, Track } from 'react-native-audio-browser'

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
  if (track.imageRow) {
    return (
      <ImageRowListItem track={track} onPress={onPress} />
    )
  }

  return (
    <TouchableOpacity
      style={[styles.item, isActive && styles.activeItem]}
      onPress={onPress}
      activeOpacity={0.7}
    >
      {track.artworkSource ? (
        <Image source={track.artworkSource} style={styles.itemArtworkLeft} />
      ) : (
        <View style={[styles.itemArtworkLeft, styles.itemArtworkPlaceholder]}>
          <Icon name="music" size={16} color="#555555" iconStyle="solid" />
        </View>
      )}
      <View style={styles.itemContent}>
        <Text style={[styles.itemTitle, isActive && styles.activeItemTitle]} numberOfLines={2}>
          {track.title}
        </Text>
        {track.subtitle && (
          <Text
            style={[styles.itemSubtitle, isActive && styles.activeItemSubtitle]}
            numberOfLines={1}
          >
            {track.subtitle}
          </Text>
        )}
        {track.artist && (
          <Text
            style={[styles.itemArtist, isActive && styles.activeItemArtist]}
            numberOfLines={1}
          >
            {track.artist}
          </Text>
        )}
      </View>
      {!track.src && (
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

function ImageRowListItem({
  track,
  onPress
}: {
  track: Track
  onPress: () => void
}) {
  const handleImageRowItemPress = (item: ImageRowItem) => {
    if (item.url) {
      navigate(item.url)
    }
  }

  return (
    <View style={styles.imageRowContainer}>
      <TouchableOpacity style={styles.imageRowHeader} onPress={onPress}>
        <Text style={styles.imageRowTitle}>{track.title}</Text>
        <Icon
          name="chevron-right"
          size={14}
          color="#ffffff"
          iconStyle="solid"
        />
      </TouchableOpacity>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.imageRowScroll}
      >
        {track.imageRow!.map((item, index) => (
          <TouchableOpacity
            key={`${item.title}-${index}`}
            style={styles.imageRowItem}
            onPress={() => handleImageRowItemPress(item)}
          >
            {item.artworkSource ? (
              <Image
                source={item.artworkSource}
                style={styles.imageRowArtwork}
              />
            ) : (
              <View style={[styles.imageRowArtwork, styles.imageRowPlaceholder]}>
                <Icon
                  name="music"
                  size={32}
                  color="#555555"
                  iconStyle="solid"
                />
              </View>
            )}
            <Text style={styles.imageRowItemTitle} numberOfLines={2}>
              {item.title}
            </Text>
          </TouchableOpacity>
        ))}
      </ScrollView>
    </View>
  )
}

const IMAGE_ROW_SIZE = 120

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
  itemArtworkLeft: {
    width: 48,
    height: 48,
    borderRadius: 4,
    marginRight: 12
  },
  itemArtworkPlaceholder: {
    backgroundColor: '#2a2a2a',
    justifyContent: 'center',
    alignItems: 'center'
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
  },
  imageRowContainer: {
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#222222'
  },
  imageRowHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    marginBottom: 12
  },
  imageRowTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#ffffff'
  },
  imageRowScroll: {
    paddingHorizontal: 16,
    gap: 12
  },
  imageRowItem: {
    width: IMAGE_ROW_SIZE
  },
  imageRowArtwork: {
    width: IMAGE_ROW_SIZE,
    height: IMAGE_ROW_SIZE,
    borderRadius: 8
  },
  imageRowPlaceholder: {
    backgroundColor: '#2a2a2a',
    justifyContent: 'center',
    alignItems: 'center'
  },
  imageRowItemTitle: {
    fontSize: 12,
    color: '#cccccc',
    marginTop: 6
  }
})
