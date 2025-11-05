import TrackPlayer from 'react-native-audio-browser';

export function configurePlayer() {
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
  });

  // If you want to override the default behavior, use handleRemote* functions:
  // Example: Override the default pause behavior
  TrackPlayer.handleRemotePause(() => {
    console.log('Event.RemotePause');
    TrackPlayer.pause();
  });

  // If you just want to log events without overriding default behavior, use on* functions:
  TrackPlayer.onRemotePlay(() => {
    console.log('Event.RemotePlay');
  });

  TrackPlayer.onMetadataChapterReceived(event => {
    console.log('onMetadataChapterReceived', event);
  });

  TrackPlayer.onMetadataTimedReceived(event => {
    console.log('onMetadataTimedReceived', event);
  });

  TrackPlayer.onMetadataCommonReceived(event => {
    console.log('onMetadataCommonReceived', event);
  });
}
