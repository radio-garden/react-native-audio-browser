import Icon from '@react-native-vector-icons/fontawesome6';
import {
  ActivityIndicator,
  StyleSheet,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import AudioBrowser, { usePlayingState } from 'react-native-audio-browser';

export function PlayPauseButton() {
  const { playing, buffering } = usePlayingState();
  return (
    <View style={styles.container}>
      {buffering ? (
        <ActivityIndicator />
      ) : (
        <TouchableWithoutFeedback onPress={AudioBrowser.togglePlayback}>
          <Icon
            name={playing ? 'pause' : 'play'}
            size={48}
            color="white"
            iconStyle="solid"
          />
        </TouchableWithoutFeedback>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    height: 50,
    width: 120,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
