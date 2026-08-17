import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { defineConfig, type DefaultTheme, type HeadConfig } from 'vitepress'
import llmstxt, { copyOrDownloadAsMarkdownButtons } from 'vitepress-plugin-llms'
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

// Hoisted out of themeConfig because vitepress-plugin-llms groups llms.txt by
// sidebar section, and the TypeDoc half of the sidebar isn't usable there.
const guideSidebar: DefaultTheme.SidebarItem[] = [
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
      { text: 'Live Streams', link: '/guide/live-streams' },
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
]

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

    // Emit links without the .html suffix. Cloudflare Pages serves foo.html at
    // /foo natively, and so does GitHub Pages, so this works on both hosts.
    // Without it every internal link is a 308 redirect on Cloudflare.
    cleanUrls: true,

    // The Pages project also answers on audiobrowser.pages.dev, serving the same
    // production build, so a canonical URL keeps the two from competing in
    // search results.
    //
    // Skipped for noindex builds: noindex and a canonical pointing elsewhere are
    // contradictory signals, and previews are already noindexed.
    transformPageData(pageData) {
      if (noindex) return

      const path = pageData.relativePath
        .replace(/(^|\/)index\.md$/, '$1')
        .replace(/\.md$/, '')

      pageData.frontmatter.head ??= []
      pageData.frontmatter.head.push([
        'link',
        { rel: 'canonical', href: `https://audiobrowser.dev/${path}` }
      ])
    },

    // Agent-readable output: a .md twin of every page, plus /llms.txt (the
    // index) and /llms-full.txt (the guide in one file). Agents fetch URLs and
    // their HTML-to-text conversion mangles exactly what these guides lean on —
    // fenced code, wide tables, ::: containers, code-group tabs — so the raw
    // Markdown is what they should be reading.
    vite: {
      plugins: [
        llmstxt({
          // Absolute links only make sense for the canonical build. A share or
          // preview deploy lives under a DOCS_BASE subpath, where site-relative
          // links are the ones that resolve.
          domain: noindex ? undefined : 'https://audiobrowser.dev',

          // The home page is `layout: home`, so it has neither an h1 nor a
          // description for the plugin to lift the llms.txt header from.
          title: 'Audio Browser',
          description:
            'React Native audio for apps that span app screens, lock screens, CarPlay, Android Auto, voice controls, and the web, with one shared playback and browse model.',

          // TypeDoc copies CHANGELOG / CONTRIBUTING / CODE_OF_CONDUCT into the
          // reference. They belong on the site, not in an agent's context.
          ignoreFiles: ['api/_media/**'],

          // llms-full.txt is the guide alone. Folding in the TypeDoc reference
          // roughly doubles it (~110k tokens), and an agent working inside a
          // consumer app already has those signatures as .d.ts in node_modules.
          // api.md is the README, which the guide already covers. Both stay
          // listed in llms.txt and available as their own .md.
          ignoreFilesPerOutput: {
            llmsFullTxt: ['api/**', 'api.md']
          },

          // The TypeDoc sidebar is a per-symbol anchor tree — its groups don't
          // correspond to pages, so handing it over yields dozens of empty
          // sections. Guide groups only; the reference pages get grouped under
          // "Other", each with the title read from its own h1.
          sidebar: guideSidebar
        })
      ]
    },

    markdown: {
      // Renders the "Copy as Markdown" / "Download as Markdown" pair below the
      // h1 of every page. Pairs with the theme's component registration.
      config(md) {
        md.use(copyOrDownloadAsMarkdownButtons)
      }
    },

    // Emitted here rather than committed to public/ because the noindex half is
    // build-conditional — a noindex header file sitting in public/ would follow
    // a merge straight onto audiobrowser.dev.
    async buildEnd({ outDir }) {
      // Cloudflare Pages otherwise serves the .md twins as a download in some
      // browsers, and text/plain for .txt keeps llms.txt viewable in-tab.
      const rules = [
        '/*.md\n  Content-Type: text/markdown; charset=utf-8\n',
        '/*.txt\n  Content-Type: text/plain; charset=utf-8\n'
      ]

      if (noindex) {
        rules.unshift('/*\n  X-Robots-Tag: noindex, nofollow\n')
      }

      await writeFile(join(outDir, '_headers'), rules.join('\n'))
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
        '/guide/': guideSidebar,
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
