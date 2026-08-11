import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { defineConfig, type HeadConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'
import typedocSidebar from '../api/typedoc-sidebar.json'

// Keep a deploy out of search indexes. Set DOCS_NOINDEX=1 for anything that is
// not the canonical audiobrowser.dev site — a Cloudflare Pages preview
// deployment, or a one-off share link. Off by default so the production build
// is never accidentally deindexed.
//
// X-Robots-Tag is what deindexes, and it covers non-HTML assets too; Cloudflare
// Pages reads it from _headers. The meta tag is the fallback for hosts that
// ignore _headers.
const noindex = process.env.DOCS_NOINDEX === '1'

const head: HeadConfig[] = [['link', { rel: 'icon', href: '/favicon.ico' }]]

if (noindex) {
  head.push(['meta', { name: 'robots', content: 'noindex, nofollow' }])
}

export default withMermaid(
  defineConfig({
    title: 'Audio Browser',
    description:
      'React Native audio module with browsable navigation trees and native Android Auto/CarPlay integration.',

    head,

    ignoreDeadLinks: true,

    // Emit links without the .html suffix. Cloudflare Pages serves foo.html at
    // /foo natively, and so does GitHub Pages, so this works on both hosts.
    // Without it every internal link is a 308 redirect on Cloudflare.
    cleanUrls: true,

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
              { text: 'Basic Usage', link: '/guide/basic-usage' }
            ]
          },
          {
            text: 'Platform Setup',
            items: [
              { text: 'Android Auto', link: '/guide/android-auto' },
              { text: 'CarPlay', link: '/guide/carplay' }
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
