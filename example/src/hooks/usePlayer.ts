import { useEffect, useState } from 'react';
import TrackPlayer from 'react-native-audio-browser';
import { tracks } from '../services';

export function useSetupPlayer() {
  const [playerReady, setPlayerReady] = useState(false);
  useEffect(() => {
    let unmounted = false;
    (async () => {
      try {
        await TrackPlayer.setupPlayer();
      } catch (error) {
        console.error('Error setting up player:', error);
        throw error;
      }
      TrackPlayer.updateOptions({
        android: {
          appKilledPlaybackBehavior: 'stop-playback-and-remove-notification',
          notificationCapabilities: [
            'play',
            'pause',
            'seek-to',
            'skip-to-next',
            'skip-to-previous',
          ],
        },
        capabilities: [
          'play',
          'pause',
          'skip-to-next',
          'skip-to-previous',
          'seek-to',
          'jump-backward',
          'jump-forward',
        ],
        progressUpdateEventInterval: 2
      });
      if (unmounted) return;
      setPlayerReady(true);
      if (TrackPlayer.getQueue().length <= 0) {
        TrackPlayer.setQueue(tracks);
      }
    })();
    return () => {
      unmounted = true;
    };
  }, []);
  return playerReady;
}
