import { useEffect, useState } from 'react';
import TrackPlayer from 'react-native-audio-browser';
import { tracks } from './tracks';

export function useSetupPlayer() {
  const [playerReady, setPlayerReady] = useState(false);
  useEffect(() => {
    TrackPlayer.setupPlayer()
      .then(() => {
        setPlayerReady(true);
        if (TrackPlayer.getQueue().length <= 0) {
          TrackPlayer.setQueue(tracks);
        }
      })
      .catch(error => {
        console.error('Error in setupPlayer:', error);
      });
  }, []);
  return playerReady;
}
