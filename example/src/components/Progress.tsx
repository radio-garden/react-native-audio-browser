import Slider from '@react-native-community/slider';
import { Dimensions, StyleSheet, Text, View } from 'react-native';
import TrackPlayer, { usePolledProgress } from 'react-native-audio-browser';
import { Spacer } from './Spacer';

export function Progress({ live }: { live?: boolean }) {
  const { position, duration } = usePolledProgress();

  // This is a workaround since the slider component only takes absolute widths
  const progressBarWidth = Dimensions.get('window').width * 0.92;

  return (
    <View style={styles.container}>
      {live || duration === Infinity ? (
        <Text style={styles.liveText}>Live Stream</Text>
      ) : (
        <View>
          <Slider
            tapToSeek
            style={{ ...styles.slider, width: progressBarWidth }}
            value={position}
            minimumValue={0}
            maximumValue={duration}
            thumbTintColor="#FFD479"
            minimumTrackTintColor="#FFD479"
            maximumTrackTintColor="#FFFFFF"
            onSlidingComplete={TrackPlayer.seekTo}
          />
          <View style={styles.labelContainer}>
            <Text style={styles.labelText}>{formatSeconds(position)}</Text>
            <Spacer mode={'expand'} />
            <Text style={styles.labelText}>
              {formatSeconds(Math.max(0, duration - position))}
            </Text>
          </View>
        </View>
      )}
    </View>
  );
}

const formatSeconds = (time: number) =>
  new Date(time * 1000).toISOString().slice(14, 19);

const styles = StyleSheet.create({
  container: {
    height: 80,
    width: '90%',
    alignItems: 'center',
    justifyContent: 'center',
  },
  liveText: {
    fontSize: 18,
    color: 'white',
    alignSelf: 'center',
  },
  slider: {
    height: 40,
    marginTop: 25,
    flexDirection: 'row',
  },
  labelContainer: {
    flexDirection: 'row',
  },
  labelText: {
    color: 'white',
    fontVariant: ['tabular-nums'],
  },
});
