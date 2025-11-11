import React, { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Image,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  navigate,
  skipToNext,
  skipToPrevious,
  togglePlayback,
  Track,
  useActiveTrack,
  useContent,
  usePath,
  usePlayingState,
  useTabs,
} from 'react-native-audio-browser';

export function BrowserScreen() {
  const path = usePath();
  const content = useContent();
  const track = useActiveTrack();
  const tabs = useTabs();

  // Track navigation history - initialize with current path
  const [history, setHistory] = useState<string[]>(() => (path ? [path] : []));
  const isNavigatingBackRef = useRef(false);

  // Update history when path changes
  useEffect(() => {
    if (!path) return;

    if (isNavigatingBackRef.current) {
      // We're navigating back, don't add to history
      isNavigatingBackRef.current = false;
      return;
    }

    // Check if this path is a tab root
    const isTabRoot = tabs?.some(tab => tab.url === path);

    setHistory(prev => {
      const currentTop = prev[prev.length - 1];

      // If history is empty, this is the first path - always add it
      if (prev.length === 0) {
        console.log('Initializing history with first path:', path);
        return [path];
      }

      // If navigating to a tab root, reset history to just that tab
      if (isTabRoot) {
        console.log('Navigating to tab root, resetting history:', path);
        return [path];
      }

      // Check if this is a new path (not already at the top of history)
      if (path !== currentTop) {
        const newHistory = [...prev, path];
        console.log('Adding to history:', path, 'New history:', newHistory);
        return newHistory;
      }
      return prev;
    });
  }, [path, tabs]);

  const handleBackPress = () => {
    if (history.length > 1) {
      setHistory(history => history.slice(0, -1));
      isNavigatingBackRef.current = true;
      void navigate(history[history.length - 2]);
    }
  };

  // Don't show back button if we're on a tab root
  const isOnTabRoot = tabs?.some(tab => tab.url === path);
  const canGoBack = history.length > 1 && !isOnTabRoot;

  const renderItem = ({ item }: { item: Track }) => {
    const isActive = 'src' in item && track?.src === item.src;

    return (
      <TouchableOpacity
        style={[styles.item, isActive && styles.activeItem]}
        onPress={() => {
          void navigate(item);
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
                isActive && styles.activeItemSubtitle,
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
        ) : (
          <Text style={styles.itemIcon}>{item.url ? '📁' : '🎵'}</Text>
        )}
      </TouchableOpacity>
    );
  };

  const playingState = usePlayingState();

  return (
    <View style={styles.container}>
      {/* Header with back button and current path */}
      <View style={styles.header}>
        {canGoBack && (
          <TouchableOpacity style={styles.backButton} onPress={handleBackPress}>
            <Text style={styles.backButtonText}>← Back</Text>
          </TouchableOpacity>
        )}
        <Text style={styles.pathText}>{path}</Text>
      </View>

      {/* Content */}
      <View style={styles.content}>
        {content ? (
          <>
            <Text style={styles.title}>
              {content?.title ?? path ?? 'Music Browser'}
            </Text>

            <FlatList
              data={content?.children || []}
              renderItem={renderItem}
              keyExtractor={(item, index) => `${item.title}-${index}`}
              style={styles.list}
              showsVerticalScrollIndicator={false}
            />
          </>
        ) : (
          <View style={styles.centered}>
            <ActivityIndicator size="large" color="#007AFF" />
            <Text style={styles.loadingText}>
              Loading {path ? path : ''}...
            </Text>
          </View>
        )}
      </View>

      {/* Mini Player */}
      {track && (
        <View style={styles.miniPlayer}>
          <View style={styles.miniPlayerInfo}>
            {track.artwork ? (
              <Image
                source={{ uri: track.artwork }}
                style={styles.miniPlayerArtwork}
              />
            ) : (
              <View style={styles.miniPlayerArtworkPlaceholder}>
                <Text style={styles.miniPlayerArtworkEmoji}>🎵</Text>
              </View>
            )}
            <View style={styles.miniPlayerText}>
              <Text style={styles.miniPlayerTitle} numberOfLines={1}>
                {track.title}
              </Text>
              <Text style={styles.miniPlayerArtist} numberOfLines={1}>
                {track.artist || 'Unknown Artist'}
              </Text>
            </View>
          </View>
          <View style={styles.miniPlayerControls}>
            <TouchableOpacity
              style={styles.miniControlButton}
              onPress={() => skipToPrevious()}
            >
              <Text style={styles.miniControlText}>⏮</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.miniControlButton}
              onPress={togglePlayback}
            >
              <Text style={styles.miniControlText}>
                {playingState.playing ? '⏸' : '▶️'}
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.miniControlButton}
              onPress={() => skipToNext()}
            >
              <Text style={styles.miniControlText}>⏭</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {/* Tab Bar */}
      {tabs && tabs.length > 0 && (
        <View style={styles.tabBar}>
          {tabs.map((tab, index) => (
            <TouchableOpacity
              key={index}
              style={[styles.tab, tab.url === path && styles.activeTab]}
              onPress={() => {
                void navigate(tab);
              }}
            >
              <Text
                style={[
                  styles.tabText,
                  tab.url === path && styles.activeTabText,
                ]}
              >
                {tab.title}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#333333',
  },
  backButton: {
    marginRight: 12,
  },
  backButtonText: {
    color: '#007AFF',
    fontSize: 16,
  },
  pathText: {
    color: '#888888',
    fontSize: 14,
  },
  content: {
    flex: 1,
    padding: 16,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 16,
  },
  playAllButton: {
    backgroundColor: '#007AFF',
    padding: 12,
    borderRadius: 8,
    marginBottom: 16,
    alignItems: 'center',
  },
  playAllButtonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '600',
  },
  list: {
    flex: 1,
  },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#222222',
  },
  itemContent: {
    flex: 1,
  },
  itemTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 2,
  },
  itemSubtitle: {
    fontSize: 14,
    color: '#888888',
    marginBottom: 2,
  },
  itemArtist: {
    fontSize: 14,
    color: '#666666',
  },
  itemIcon: {
    fontSize: 24,
    marginLeft: 12,
  },
  itemArtwork: {
    width: 48,
    height: 48,
    borderRadius: 4,
    marginLeft: 12,
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#000000',
  },
  loadingText: {
    color: '#888888',
    marginTop: 12,
    fontSize: 16,
  },
  errorText: {
    color: '#ff4444',
    fontSize: 16,
    textAlign: 'center',
    marginBottom: 16,
  },
  retryButton: {
    backgroundColor: '#007AFF',
    padding: 12,
    borderRadius: 8,
  },
  retryButtonText: {
    color: '#ffffff',
    fontSize: 16,
  },
  activeItem: {
    backgroundColor: '#1a1a1a',
    borderLeftWidth: 3,
    borderLeftColor: '#007AFF',
  },
  activeItemTitle: {
    color: '#007AFF',
  },
  activeItemSubtitle: {
    color: '#aaaaaa',
  },
  activeItemArtist: {
    color: '#888888',
  },
  tabBar: {
    flexDirection: 'row',
    backgroundColor: '#1a1a1a',
    borderTopWidth: 1,
    borderTopColor: '#333333',
    paddingBottom: 20,
    paddingTop: 10,
  },
  tab: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 10,
    paddingHorizontal: 15,
  },
  activeTab: {
    borderBottomWidth: 2,
    borderBottomColor: '#007AFF',
  },
  tabText: {
    color: '#888888',
    fontSize: 14,
    fontWeight: '500',
  },
  activeTabText: {
    color: '#007AFF',
    fontWeight: '600',
  },
  miniPlayer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#1a1a1a',
    borderTopWidth: 1,
    borderTopColor: '#333333',
    padding: 12,
  },
  miniPlayerInfo: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    marginRight: 12,
  },
  miniPlayerArtwork: {
    width: 48,
    height: 48,
    borderRadius: 4,
    marginRight: 12,
  },
  miniPlayerArtworkPlaceholder: {
    width: 48,
    height: 48,
    borderRadius: 4,
    backgroundColor: '#333333',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  miniPlayerArtworkEmoji: {
    fontSize: 24,
  },
  miniPlayerText: {
    flex: 1,
  },
  miniPlayerTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 2,
  },
  miniPlayerArtist: {
    fontSize: 12,
    color: '#888888',
  },
  miniPlayerControls: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  miniControlButton: {
    padding: 8,
    marginLeft: 4,
  },
  miniControlText: {
    fontSize: 20,
    color: '#ffffff',
  },
});
