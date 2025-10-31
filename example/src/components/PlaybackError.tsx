import { StyleSheet, Text, View } from 'react-native';
import { usePlaybackError } from 'react-native-audio-browser';

export function PlaybackError() {
  const error = usePlaybackError()?.message;

  if (!error) return null;

  return (
    <View style={styles.container}>
      <Text style={styles.text}>{error}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    width: '100%',
    marginVertical: 24,
    alignSelf: 'center',
  },
  text: {
    color: 'red',
    width: '100%',
    textAlign: 'center',
  },
});
