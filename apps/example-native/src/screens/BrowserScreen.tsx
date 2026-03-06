import Icon from '@react-native-vector-icons/fontawesome6'
import React, { useEffect, useState } from 'react'
import {
  ActivityIndicator,
  FlatList,
  StyleSheet,
  Text,
  TouchableOpacity,
  View
} from 'react-native'
import {
  hasSearch,
  navigate,
  Track,
  useActiveTrack,
  useContent,
  useFormattedNavigationError,
  usePath,
  useTabs
} from 'react-native-audio-browser'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { FullScreenPlayer } from '../components/FullScreenPlayer'
import { MiniPlayer } from '../components/MiniPlayer'
import { NavigationErrorView } from '../components/NavigationErrorView'
import { TrackListItem } from '../components/TrackListItem'
import { useBrowserHistory } from '../hooks/useBrowserHistory'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { SearchScreen } from './SearchScreen'

type Screen = 'browser' | 'search'

export function BrowserScreen() {
  const insets = useSafeAreaInsets()
  const path = usePath()
  const content = useContent()
  const activeTrack = useActiveTrack()
  const tabs = useTabs()
  const navigationError = useFormattedNavigationError()
  const handleBrowserBack = useBrowserHistory()
  const [showPlayer, setShowPlayer] = useState(false)
  const [screenStack, setScreenStack] = useState<Screen[]>(['browser'])
  const [searchQuery, setSearchQuery] = useState('')
  const showLoading = useDebouncedValue(!content, true)

  const currentScreen = screenStack[screenStack.length - 1]
  const showSearch = currentScreen === 'search'

  const pushScreen = (screen: Screen) => {
    setScreenStack((stack) => [...stack, screen])
  }

  const popScreen = () => {
    setScreenStack((stack) => (stack.length > 1 ? stack.slice(0, -1) : stack))
  }

  // Can go back if we have screen history OR browser history
  const canGoBack = screenStack.length > 1 || handleBrowserBack

  const handleBack = () => {
    if (screenStack.length > 1) {
      popScreen()
    } else if (handleBrowserBack) {
      handleBrowserBack()
    }
  }

  // Reset stack when navigating to a tab root
  useEffect(() => {
    const isTabRoot = tabs?.some((tab) => tab.url === path)
    if (isTabRoot) {
      setScreenStack(['browser'])
    }
  }, [path, tabs])

  const renderItem = ({ item }: { item: Track }) => (
    <TrackListItem
      track={item}
      isActive={item.src != null && activeTrack?.src === item.src}
      onPress={() => navigate(item)}
    />
  )

  return (
    <View style={styles.container}>
      {/* Header with back button and current path */}
      <View
        style={[
          styles.header,
          { marginTop: -insets.top, paddingTop: 16 + insets.top }
        ]}
      >
        {canGoBack && (
          <TouchableOpacity style={styles.backButton} onPress={handleBack}>
            <Icon
              name="chevron-left"
              size={18}
              color="#888888"
              iconStyle="solid"
            />
          </TouchableOpacity>
        )}
        <Text style={styles.headerTitle} numberOfLines={1}>
          {showSearch ? 'Search' : content?.title ?? ''}
        </Text>
        {hasSearch() && (
          <TouchableOpacity
            style={styles.searchButton}
            onPress={() => {
              if (showSearch) {
                popScreen()
              } else {
                setSearchQuery('')
                pushScreen('search')
              }
            }}
          >
            <Icon
              name="magnifying-glass"
              size={18}
              color={showSearch ? '#007AFF' : '#888888'}
              iconStyle="solid"
            />
          </TouchableOpacity>
        )}
      </View>

      {/* Content */}
      <View style={styles.content}>
        {showSearch ? (
          <SearchScreen
            query={searchQuery}
            onQueryChange={setSearchQuery}
            onNavigate={() => pushScreen('browser')}
          />
        ) : navigationError ? (
          <NavigationErrorView
            error={navigationError}
            onRetry={() => {
              if (path) {
                navigate(path)
              }
            }}
          />
        ) : showLoading ? (
          <View style={styles.centered}>
            <ActivityIndicator size="large" color="#007AFF" />
          </View>
        ) : content ? (
          <>
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

      <MiniPlayer onPress={() => setShowPlayer(true)} />

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
                navigate(tab)
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

      <FullScreenPlayer
        visible={showPlayer}
        onClose={() => setShowPlayer(false)}
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
    flexShrink: 0,
    padding: 8,
    marginLeft: -8,
    marginRight: 4
  },
  searchButton: {
    flexShrink: 0,
    padding: 8,
    marginRight: -8,
    marginLeft: 4
  },
  headerTitle: {
    flex: 1,
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '600',
  },
  content: {
    flex: 1,
  },
  list: {
    flex: 1
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#000000'
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
