import { ScrollView, StyleSheet } from 'react-native';
import TrackPlayer from 'react-native-audio-browser';
import { Button } from './Button';
import { Spacer } from './Spacer';

export function ActionSheet() {
  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Spacer />
      <Button
        title="Update Notification Metadata Randomly"
        onPress={() => {
          const randomTitle = Math.random().toString(36).substring(7);
          TrackPlayer.updateNowPlayingMetadata({
            title: `Random: ${randomTitle}`,
            artwork: `https://random.imagecdn.app/800/800?dummy=${Date.now()}`,
          });
        }}
        type="primary"
      />
      <Button
        title="Update Current Track Metadata Randomly"
        onPress={() => {
          const currentTrackIndex = TrackPlayer.getActiveTrackIndex();
          if (currentTrackIndex !== undefined) {
            const randomTitle = Math.random().toString(36).substring(7);
            TrackPlayer.updateMetadataForTrack(currentTrackIndex, {
              title: `Random: ${randomTitle}`,
              artwork: `https://random.imagecdn.app/800/800?dummy=${Date.now()}`,
              duration: Math.floor(Math.random() * 300), // 0-300 seconds
            });
          }
        }}
        type="primary"
      />
      <Button title="Reset" onPress={TrackPlayer.reset} type="primary" />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: 16,
  },
});
