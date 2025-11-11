/**
 * AudioBrowser Shell Configuration
 * 
 * This is a simple default configuration for the shell.
 * Replace this entire object when creating specific examples.
 */

export const audioBrowserConfig = {
  routes: {
    '/': {
      title: 'Music Library',
      children: [
        { 
          title: 'Rock', 
          url: '/rock', 
          icon: '🎸',
          subtitle: 'Rock music collection'
        },
        { 
          title: 'Jazz', 
          url: '/jazz', 
          icon: '🎷',
          subtitle: 'Jazz classics'
        }
      ]
    },
    '/rock': {
      title: 'Rock Music',
      playable: true,
      children: [
        {
          title: 'Example Track',
          artist: 'Example Artist',
          album: 'Example Album',
          duration: 180,
          src: 'https://example.com/track.mp3'
        }
      ]
    },
    '/jazz': {
      title: 'Jazz Collection', 
      playable: true,
      children: []
    }
  }
}