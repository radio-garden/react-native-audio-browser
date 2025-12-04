import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Audio Browser',
  description: 'React Native audio module with browsable navigation trees and native Android Auto/CarPlay integration.',

  head: [
    ['link', { rel: 'icon', href: '/favicon.ico' }]
  ],

  themeConfig: {
    search: {
      provider: 'local'
    },

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
      '/api/': [
        {
          text: 'API Reference',
          items: [
            { text: 'Overview', link: '/api/' },
            { text: 'Track', link: '/api/track' },
            { text: 'Events', link: '/api/events' }
          ]
        }
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/radio-garden/react-native-audio-browser' }
    ]
  }
})
