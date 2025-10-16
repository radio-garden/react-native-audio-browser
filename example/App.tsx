import React, { useEffect } from 'react';
import {
  Text,
  View,
  StyleSheet,
  FlatList,
  TouchableOpacity,
} from 'react-native';
import { configure, useAudioBrowser } from 'react-native-audio-browser';

// Configure the audio browser on app start
configure({
  api: 'https://api.radiogarden.com/api/v2',
  tree: [
    {
      title: 'Browse',
      path: '/browse',
      icon: 'browse',
      platforms: ['android-auto', 'carplay', 'mobile'],
    },
    {
      title: 'Search',
      path: '/search',
      icon: 'search',
      platforms: ['android-auto', 'carplay', 'mobile'],
    },
    {
      title: 'Favorites',
      path: '/favorites',
      icon: 'heart',
      handler: async (path: string) => {
        // Custom handler for favorites
        return {
          url: path,
          title: 'My Favorites',
          children: [
            {
              url: '/station/radio1',
              title: 'Radio 1',
              subtitle: 'Amsterdam',
              src: 'https://stream.radio1.nl/stream',
            },
          ],
        };
      },
    },
    {
      title: 'Recent',
      path: '/recent',
      icon: 'clock',
      platforms: ['mobile'], // Mobile only
    },
  ],
});

function App(): React.JSX.Element {
  const {
    currentPath,
    currentNode,
    canGoBack,
    isConnectedToAndroidAuto,
    isConnectedToCarPlay,
    isLoading,
    error,
    navigate,
    goBack,
    refresh,
  } = useAudioBrowser();

  return (
    <View style={styles.container}>
      {/* Status */}
      <View style={styles.status}>
        <Text style={styles.statusText}>Path: {currentPath}</Text>
        <Text style={styles.statusText}>
          Car:{' '}
          {isConnectedToAndroidAuto
            ? 'Android Auto'
            : isConnectedToCarPlay
              ? 'CarPlay'
              : 'None'}
        </Text>
        {isLoading && <Text style={styles.statusText}>Loading...</Text>}
        {error && <Text style={styles.errorText}>Error: {error.message}</Text>}
      </View>

      {/* Navigation */}
      {canGoBack && (
        <TouchableOpacity style={styles.button} onPress={goBack}>
          <Text style={styles.buttonText}>← Back</Text>
        </TouchableOpacity>
      )}

      {/* Content */}
      <FlatList
        data={currentNode?.children || []}
        keyExtractor={item => item.url}
        renderItem={({ item }) => (
          <TouchableOpacity
            style={styles.item}
            onPress={() => {
              if ('href' in item) {
                navigate(item.href);
              } else if ('src' in item) {
                // Handle media item playback
                console.log('Playing:', item.title);
              }
            }}
          >
            <Text style={styles.itemTitle}>{item.title}</Text>
            {item.subtitle && (
              <Text style={styles.itemSubtitle}>{item.subtitle}</Text>
            )}
            <Text style={styles.itemType}>
              {'src' in item ? '♪ Playable' : '📁 Folder'}
            </Text>
          </TouchableOpacity>
        )}
      />

      {/* Refresh */}
      <TouchableOpacity style={styles.button} onPress={refresh}>
        <Text style={styles.buttonText}>Refresh</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingTop: 50,
    paddingHorizontal: 20,
  },
  status: {
    padding: 10,
    backgroundColor: '#f0f0f0',
    marginBottom: 10,
  },
  statusText: {
    fontSize: 14,
    marginBottom: 5,
  },
  errorText: {
    color: 'red',
    fontSize: 14,
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 15,
    borderRadius: 8,
    marginBottom: 10,
    alignItems: 'center',
  },
  buttonText: {
    color: 'white',
    fontSize: 16,
    fontWeight: 'bold',
  },
  item: {
    padding: 15,
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
  },
  itemTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    marginBottom: 5,
  },
  itemSubtitle: {
    fontSize: 14,
    color: '#666',
    marginBottom: 5,
  },
  itemType: {
    fontSize: 12,
    color: '#999',
  },
});

export default App;
