import DefaultTheme from 'vitepress/theme'
import { h } from 'vue'
import LiveDemo from './LiveDemo.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout() {
    // Embed the real example-web app (live, via iframe) in the hero image slot.
    // Swap back to ./BrowseDemo.vue for the lightweight static mock.
    return h(DefaultTheme.Layout, null, {
      'home-hero-image': () => h(LiveDemo)
    })
  }
}
