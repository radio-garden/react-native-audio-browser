import Icon from '@react-native-vector-icons/fontawesome6'
import React, { useState } from 'react'
import {
  ActivityIndicator,
  FlatList,
  Image,
  StyleSheet,
  Text,
  TouchableOpacity,
  View
} from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import {
  navigate,
  Track,
  useActiveTrack,
  useContent,
  useNavigationError,
  usePath,
  useTabs
} from 'react-native-audio-browser'
import { EqualizerModal } from '../components/EqualizerModal'
import { MiniPlayer } from '../components/MiniPlayer'
import { NavigationErrorView } from '../components/NavigationErrorView'
import { SleepTimerModal } from '../components/SleepTimerModal'
import { useBrowserHistory } from '../hooks/useBrowserHistory'
import { useDebouncedValue } from '../hooks/useDebouncedValue'

export function BrowserScreen() {
  const insets = useSafeAreaInsets()
  const path = usePath()
  const content = useContent()
  const activeTrack = useActiveTrack()
  const tabs = useTabs()
  const navigationError = useNavigationError()
  const handleBackPress = useBrowserHistory()
  const [showEqualizer, setShowEqualizer] = useState(false)
  const [showSleepTimer, setShowSleepTimer] = useState(false)
  const showLoading = useDebouncedValue(!content, true)

  const renderItem = ({ item }: { item: Track }) => {
    const isActive = item.src != null && activeTrack?.src === item.src

    return (
      <TouchableOpacity
        style={[styles.item, isActive && styles.activeItem]}
        onPress={() => {
          void navigate(item)
        }}
      >
        <View style={styles.itemContent}>
          <Text style={[styles.itemTitle, isActive && styles.activeItemTitle]}>
            {item.title}
          </Text>
          {item.subtitle && (
            <Text
              style={[
                styles.itemSubtitle,
                isActive && styles.activeItemSubtitle
              ]}
            >
              {item.subtitle}
            </Text>
          )}
          {item.artist && (
            <Text
              style={[styles.itemArtist, isActive && styles.activeItemArtist]}
            >
              {item.artist}
            </Text>
          )}
        </View>
        {item.artwork ? (
          <Image source={{ uri: item.artwork }} style={styles.itemArtwork} />
        ) : item.src ? (
          <Icon name="music" size={16} color="#ffffff" iconStyle="solid" />
        ) : (
          <Icon name="chevron-right" size={14} color="#ffffff" iconStyle="solid" />
        )}
      </TouchableOpacity>
    )
  }

  return (
    <View style={styles.container}>
      {/* Header with back button and current path */}
      <View
        style={[
          styles.header,
          { marginTop: -insets.top, paddingTop: 16 + insets.top }
        ]}
      >
        {handleBackPress && (
          <TouchableOpacity style={styles.backButton} onPress={handleBackPress}>
            <Icon name="chevron-left" size={18} color="#888888" iconStyle="solid" />
          </TouchableOpacity>
        )}
        <Text style={styles.pathText}>{path}</Text>
      </View>

      {/* Content */}
      <View style={styles.content}>
        {navigationError ? (
          <NavigationErrorView
            error={navigationError}
            onRetry={() => {
              if (path) {
                void navigate(path)
              }
            }}
          />
        ) : showLoading ? (
          <View style={styles.centered}>
            <ActivityIndicator size="large" color="#007AFF" />
            <Text style={styles.loadingText}>
              Loading {path ? path : ''}...
            </Text>
          </View>
        ) : content ? (
          <>
            <Text style={styles.title}>
              {content.title ?? path ?? 'Music Browser'}
            </Text>

            <FlatList
              data={content.children || []}
              renderItem={renderItem}
              keyExtractor={(item, index) => `${item.title}-${index}`}
              style={styles.list}
              showsVerticalScrollIndicator={false}
            />
          </>
        ) : null}
      </View>

      <MiniPlayer
        onEqualizerPress={() => setShowEqualizer(true)}
        onSleepTimerPress={() => setShowSleepTimer(true)}
      />

      {/* Tab Bar */}
      {tabs && tabs.length > 0 && (
        <View
          style={[
            styles.tabBar,
            { marginBottom: -insets.bottom, paddingBottom: insets.bottom }
          ]}
        >
          {tabs.map((tab, index) => (
            <TouchableOpacity
              key={index}
              style={styles.tab}
              onPress={() => {
                void navigate(tab)
              }}
            >
              <Text
                style={[
                  styles.tabText,
                  tab.url === path && styles.activeTabText
                ]}
              >
                {tab.title}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      )}

      <EqualizerModal
        visible={showEqualizer}
        onClose={() => setShowEqualizer(false)}
      />

      <SleepTimerModal
        visible={showSleepTimer}
        onClose={() => setShowSleepTimer(false)}
      />
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000'
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
    backgroundColor: '#1a1a1a'
  },
  backButton: {
    marginRight: 12
  },
  pathText: {
    flex: 1,
    color: '#888888',
    fontSize: 14,
    textAlign: 'right'
  },
  content: {
    flex: 1,
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
  playAllButton: {
    backgroundColor: '#007AFF',
    padding: 12,
    borderRadius: 8,
    marginBottom: 16,
    alignItems: 'center'
  },
  playAllButtonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '600'
  },
  list: {
    flex: 1
  },
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
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#000000'
  },
  loadingText: {
    color: '#888888',
    marginTop: 12,
    fontSize: 16
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
  tabBar: {
    flexDirection: 'row',
    backgroundColor: '#1a1a1a',
    paddingTop: 10
  },
  tab: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 10,
    paddingHorizontal: 15
  },
  tabText: {
    color: '#888888',
    fontSize: 14,
    fontWeight: '500'
  },
  activeTabText: {
    color: '#007AFF',
    fontWeight: '600'
  }
})
