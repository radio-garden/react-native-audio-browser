import Icon from '@react-native-vector-icons/fontawesome6';
import { StyleSheet, TouchableWithoutFeedback, View } from 'react-native';
import TrackPlayer from 'react-native-audio-browser';

import { PlaybackError } from './PlaybackError';
import { PlayPauseButton } from './PlayPauseButton';

const performSkipToNext = () => TrackPlayer.skipToNext();
const performSkipToPrevious = () => TrackPlayer.skipToPrevious();

export function PlayerControls() {
  return (
    <View style={styles.container}>
      <View style={styles.row}>
        <TouchableWithoutFeedback onPress={performSkipToPrevious}>
          <Icon name="backward" size={30} color="white" iconStyle="solid" />
        </TouchableWithoutFeedback>
        <PlayPauseButton />
        <TouchableWithoutFeedback onPress={performSkipToNext}>
          <Icon name="forward" size={30} color="white" iconStyle="solid" />
        </TouchableWithoutFeedback>
      </View>
      <PlaybackError />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    width: '100%',
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-evenly',
    alignItems: 'center',
  },
});
