'use client';

import { BrowserScreen, configureBasicBrowser } from 'common-app';
import { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';


const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    flexGrow: 1,
    justifyContent: 'center',
    backgroundColor: '#1a1a1a',
    padding: 20,
  },
});

export default function TrackPlayerProvider() {
  const [isMounted, setIsMounted] = useState(false);

  useEffect(() => {
    configureBasicBrowser()
    setIsMounted(true)
  }, []);

  if (!isMounted) {
    return (
      <View style={styles.container}>
        <ActivityIndicator size="large" color="#1DB954" />
      </View>
    );
  }

  return (
    <BrowserScreen />
  );
}
