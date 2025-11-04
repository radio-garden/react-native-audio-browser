import { Image, StyleSheet, Text, View } from 'react-native';
import type { Track } from 'react-native-audio-browser';

export function TrackInfo({ track }: { track?: Track }) {
  const imageUri = track?.artwork;

  return (
    <View style={styles.container}>
      <Image style={styles.artwork} source={{ uri: imageUri }} />
      <Text style={styles.titleText}>{track?.title}</Text>
      <Text style={styles.artistText}>{track?.artist}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
  },
  artwork: {
    width: '60%',
    aspectRatio: 1,
    marginTop: '2%',
    backgroundColor: 'grey',
  },
  titleText: {
    fontSize: 18,
    fontWeight: '600',
    color: 'white',
    marginTop: 30,
  },
  artistText: {
    fontSize: 16,
    fontWeight: '200',
    color: 'white',
  },
  customData: {
    marginTop: 15,
    alignItems: 'center',
  },
  dataText: {
    fontSize: 13,
    color: 'white',
  },
});
