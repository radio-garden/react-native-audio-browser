import Icon from '@react-native-vector-icons/fontawesome6'
import React, { useEffect, useRef, useState } from 'react'
import {
  ActivityIndicator,
  FlatList,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View
} from 'react-native'
import {
  navigate,
  search,
  Track,
  useActiveTrack
} from 'react-native-audio-browser'
import { TrackListItem } from '../components/TrackListItem'

type SearchScreenProps = {
  query: string
  onQueryChange: (query: string) => void
  onNavigate?: () => void
}

export function SearchScreen({
  query,
  onQueryChange,
  onNavigate
}: SearchScreenProps) {
  const [results, setResults] = useState<Track[]>([])
  const [loading, setLoading] = useState(false)
  const activeTrack = useActiveTrack()
  const requestIdRef = useRef(0)

  useEffect(() => {
    if (!query.trim()) {
      setResults([])
      setLoading(false)
      return
    }

    const requestId = ++requestIdRef.current
    setLoading(true)

    search(query.trim())
      .then((tracks) => {
        // Only update if this is still the latest request
        if (requestId === requestIdRef.current) {
          setResults(tracks)
        }
      })
      .catch(() => {
        if (requestId === requestIdRef.current) {
          setResults([])
        }
      })
      .finally(() => {
        if (requestId === requestIdRef.current) {
          setLoading(false)
        }
      })
  }, [query])

  const renderItem = ({ item }: { item: Track }) => (
    <TrackListItem
      track={item}
      isActive={item.src != null && activeTrack?.src === item.src}
      onPress={() => {
        navigate(item)
        // Push browser on top of search when navigating to a browsable item
        if (item.url && !item.src) {
          onNavigate?.()
        }
      }}
    />
  )

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Search</Text>

      <View style={styles.searchContainer}>
        <Icon
          name="magnifying-glass"
          size={16}
          color="#666666"
          iconStyle="solid"
          style={styles.searchIcon}
        />
        <TextInput
          style={styles.searchInput}
          placeholder="Search for tracks..."
          placeholderTextColor="#666666"
          value={query}
          onChangeText={onQueryChange}
          returnKeyType="search"
          autoCapitalize="none"
          autoCorrect={false}
          autoFocus
        />
        {query.length > 0 && (
          <TouchableOpacity
            style={styles.clearButton}
            onPress={() => onQueryChange('')}
          >
            <Icon name="xmark" size={14} color="#666666" iconStyle="solid" />
          </TouchableOpacity>
        )}
      </View>

      {loading ? (
        <View style={styles.centered}>
          <ActivityIndicator size="large" color="#007AFF" />
        </View>
      ) : results.length > 0 ? (
        <FlatList
          data={results}
          renderItem={renderItem}
          keyExtractor={(item, index) => `${item.title}-${index}`}
          style={styles.list}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
        />
      ) : query.trim() ? (
        <View style={styles.centered}>
          <Icon
            name="magnifying-glass"
            size={48}
            color="#333333"
            iconStyle="solid"
          />
          <Text style={styles.emptyText}>No results found</Text>
          <Text style={styles.emptySubtext}>Try a different search term</Text>
        </View>
      ) : (
        <View style={styles.centered}>
          <Icon
            name="magnifying-glass"
            size={48}
            color="#333333"
            iconStyle="solid"
          />
          <Text style={styles.emptyText}>Search for music</Text>
          <Text style={styles.emptySubtext}>
            Try "radio", "jazz", "soul", or "david"
          </Text>
        </View>
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000',
    paddingTop: 16
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 16,
    paddingHorizontal: 16,
    paddingTop: 16
  },
  searchContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 16,
    marginBottom: 16,
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    paddingHorizontal: 12
  },
  searchIcon: {
    marginRight: 8
  },
  searchInput: {
    flex: 1,
    height: 44,
    color: '#ffffff',
    fontSize: 16
  },
  clearButton: {
    padding: 8
  },
  list: {
    flex: 1
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center'
  },
  emptyText: {
    color: '#888888',
    marginTop: 16,
    fontSize: 18,
    fontWeight: '600'
  },
  emptySubtext: {
    color: '#666666',
    marginTop: 8,
    fontSize: 14
  }
})
