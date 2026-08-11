import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { defineConfig, type HeadConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'
import typedocSidebar from '../api/typedoc-sidebar.json'

// Deploy base path. Defaults to '/' (root domain like audiobrowser.dev). When
// hosting under a subpath — e.g. share.radio.garden/<deploy-id>/ — set DOCS_BASE
// to that subpath at build time so asset URLs resolve. Must start and end with
// '/'. Example:
//   DOCS_BASE=/2026-06-26-abc123/ corepack yarn build
const rawBase = process.env.DOCS_BASE ?? '/'
const base = `/${rawBase.replace(/^\/+|\/+$/g, '')}/`.replace('//', '/')

// Keep a deploy out of search indexes. Set DOCS_NOINDEX=1 for anything that is
// not the canonical audiobrowser.dev site — a Cloudflare Pages preview
// deployment, or a one-off share link. Off by default so the production build
// is never accidentally deindexed.
//
// X-Robots-Tag is what deindexes, and it covers non-HTML assets too; Cloudflare
// Pages reads it from _headers. The meta tag is the fallback for hosts that
// ignore _headers.
const noindex = process.env.DOCS_NOINDEX === '1'

// Raw head hrefs are not auto-prefixed with base, so build it in explicitly.
const head: HeadConfig[] = [
  ['link', { rel: 'icon', href: `${base}favicon.ico` }]
]

if (noindex) {
  head.push(['meta', { name: 'robots', content: 'noindex, nofollow' }])
}

export default withMermaid(
  defineConfig({
    base,

    title: 'Audio Browser',
    description:
      'Full-featured React Native audio for production apps that span app screens, lock screens, CarPlay, Android Auto, voice controls, and the web, with one shared playback and browse model.',

    head,

    ignoreDeadLinks: true,

    // Repo-internal files that live in the site root but are not site content.
    srcExclude: ['CLAUDE.md', 'TODO.md'],

    // Emitted here rather than committed to public/ so it only ever lands in a
    // DOCS_NOINDEX build — a noindex header file sitting in public/ would follow
    // a merge straight onto audiobrowser.dev.
    async buildEnd({ outDir }) {
      if (!noindex) return

      await writeFile(
        join(outDir, '_headers'),
        '/*\n  X-Robots-Tag: noindex, nofollow\n'
      )
    },

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
