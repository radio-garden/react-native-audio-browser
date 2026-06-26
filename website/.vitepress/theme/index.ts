import DefaultTheme from 'vitepress/theme'
import { h } from 'vue'
import BrowseDemo from './BrowseDemo.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout() {
    // Render the self-navigating browse list in the hero's right-side image slot.
    return h(DefaultTheme.Layout, null, {
      'home-hero-image': () => h(BrowseDemo),
    })
  },
}
