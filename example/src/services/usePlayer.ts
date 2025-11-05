import { useEffect, useState } from 'react';
import AudioBrowser from 'react-native-audio-browser';
import { tracks } from './tracks';

export function useSetupPlayer() {
  const [playerReady, setPlayerReady] = useState(false);
  useEffect(() => {
    AudioBrowser.setupPlayer()
      .then(() => {
        setPlayerReady(true);
        if (AudioBrowser.getQueue().length <= 0) {
          AudioBrowser.setQueue(tracks);
        }
      })
      .catch(error => {
        console.error('Error in setupPlayer:', error);
      });
  }, []);
  return playerReady;
}
