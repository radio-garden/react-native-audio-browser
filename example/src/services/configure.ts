import AudioBrowser from 'react-native-audio-browser';

export function configurePlayer() {
  AudioBrowser.updateOptions({
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
  AudioBrowser.handleRemotePause(() => {
    console.log('Event.RemotePause');
    AudioBrowser.pause();
  });

  // If you just want to log events without overriding default behavior, use on* functions:
  AudioBrowser.onRemotePlay(() => {
    console.log('Event.RemotePlay');
  });

  AudioBrowser.onMetadataChapterReceived(event => {
    console.log('onMetadataChapterReceived', event);
  });

  AudioBrowser.onMetadataTimedReceived(event => {
    console.log('onMetadataTimedReceived', event);
  });

  AudioBrowser.onMetadataCommonReceived(event => {
    console.log('onMetadataCommonReceived', event);
  });
}
