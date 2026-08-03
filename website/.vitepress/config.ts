import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'
import typedocSidebar from '../api/typedoc-sidebar.json'

// Deploy base path. Defaults to '/' (root domain like audiobrowser.dev). When
// hosting under a subpath — e.g. share.radio.garden/<deploy-id>/ — set DOCS_BASE
// to that subpath at build time so asset URLs resolve. Must start and end with
// '/'. Example:
//   DOCS_BASE=/2026-06-26-abc123/ corepack yarn build
const rawBase = process.env.DOCS_BASE ?? '/'
const base = `/${rawBase.replace(/^\/+|\/+$/g, '')}/`.replace('//', '/')

export default withMermaid(
  defineConfig({
    base,

    title: 'Audio Browser',
    description:
      'Full-featured React Native audio for production apps that span app screens, lock screens, CarPlay, Android Auto, voice controls, and the web, with one shared playback and browse model.',

    // Raw head hrefs are not auto-prefixed with base, so build it in explicitly.
    head: [['link', { rel: 'icon', href: `${base}favicon.ico` }]],

    ignoreDeadLinks: true,

    // Repo-internal files that live in the site root but are not site content.
    srcExclude: ['CLAUDE.md', 'TODO.md'],

    themeConfig: {
      search: {
        provider: 'local'
      },

      outline: false,

      nav: [
        { text: 'Guide', link: '/guide/getting-started' },
        { text: 'API', link: '/api/' }
      ],

      sidebar: {
        '/guide/': [
          {
            text: 'Introduction',
            items: [
              { text: 'Getting Started', link: '/guide/getting-started' },
              { text: 'Basic Usage', link: '/guide/basic-usage' },
              { text: 'Configuration', link: '/guide/configuration' },
              { text: 'Track', link: '/guide/track' },
              { text: 'Hooks', link: '/guide/hooks' },
              {
                text: 'Track Player Migration',
                link: '/guide/migrating-from-track-player'
              }
            ]
          },
          {
            text: 'Player',
            items: [
              { text: 'Artwork', link: '/guide/artwork' },
              { text: 'Errors', link: '/guide/errors' },
              { text: 'Metadata', link: '/guide/metadata' },
              { text: 'Now Playing', link: '/guide/now-playing' },
              { text: 'Playback', link: '/guide/playback' },
              { text: 'Queue', link: '/guide/queue' },
              { text: 'Remote Controls', link: '/guide/remote-controls' }
            ]
          },
          {
            text: 'Browser',
            items: [
              { text: 'Browser', link: '/guide/browser' },
              { text: 'Favorites', link: '/guide/favorites' },
              { text: 'Gate', link: '/guide/gate' },
              { text: 'Search', link: '/guide/search' }
            ]
          },
          {
            text: 'Extras',
            items: [
              { text: 'Audio Output', link: '/guide/audio-output' },
              { text: 'Battery', link: '/guide/battery' },
              { text: 'Equalizer', link: '/guide/equalizer' },
              { text: 'Network', link: '/guide/network' },
              { text: 'Sleep Timer', link: '/guide/sleep-timer' }
            ]
          },
          {
            text: 'Automotive',
            items: [
              { text: 'Overview', link: '/guide/automotive' },
              { text: 'Android Auto', link: '/guide/android-auto' },
              { text: 'CarPlay', link: '/guide/carplay' }
            ]
          },
          {
            text: 'Troubleshooting',
            items: [
              {
                text: 'Networking in native callbacks',
                link: '/guide/native-callback-fetch'
              },
              {
                text: 'Android SSL / Trust Anchor Errors',
                link: '/guide/android-certificates'
              }
            ]
          }
        ],
        '/api/': typedocSidebar
      },

      socialLinks: [
        {
          icon: 'github',
          link: 'https://github.com/radio-garden/react-native-audio-browser'
        }
      ]
    }
  })
)
