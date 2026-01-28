import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'
import typedocSidebar from '../api/typedoc-sidebar.json'

export default withMermaid(
  defineConfig({
    title: 'Audio Browser',
    description:
      'React Native audio module with browsable navigation trees and native Android Auto/CarPlay integration.',

    head: [['link', { rel: 'icon', href: '/favicon.ico' }]],

    ignoreDeadLinks: true,

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
