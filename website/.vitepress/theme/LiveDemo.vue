<script setup lang="ts">
/**
 * Hero graphic: the real example-web app (react-native-audio-browser running on
 * the web, browsing archive.org) embedded live in an iframe. Points at the local
 * dev server for now.
 */
import { ref } from 'vue'

// On touch devices the iframe would hijack page scrolling, so gate interaction
// behind a tap: the gate lets swipes scroll the page, a tap hands off to the demo.
const activated = ref(false)

// Dev: the hot-reloading demo server. Prod: the demo built into the site at
// <base>demo/ — BASE_URL carries the deploy base (e.g. a share.radio.garden subpath).
// Point at index.html explicitly: some static hosts (share.radio.garden) don't
// resolve a directory URL to its index, so a bare ".../demo/" would 404.
const demoSrc = import.meta.env.DEV
  ? 'http://localhost:5180/demo/'
  : `${import.meta.env.BASE_URL}demo/index.html`
</script>

<template>
  <div class="live" :class="{ activated }">
    <div class="stage">
      <div class="device">
        <iframe
          class="screen"
          :src="demoSrc"
          title="Audio Browser — live demo"
          loading="lazy"
        />
        <div v-if="!activated" class="tap-gate">
          <button class="tap-pill" @click="activated = true">
            Tap to try the demo
          </button>
        </div>
      </div>
      <p class="caption">↑ Live &amp; interactive — go ahead, browse and play</p>
    </div>
  </div>
</template>

<style scoped>
.live {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 340px;
  margin: 0 auto;
  /* Same one-point perspective as the front-page demo. */
  perspective: 1100px;
  perspective-origin: 50% 38%;
}
.stage {
  /* Gentle magazine-mockup turn — carries the device AND caption together.
     Straightens to face-on on hover so it's usable. */
  transform: rotateX(3deg) rotateY(-20deg) scale(1.05);
  transition: transform 0.7s cubic-bezier(0.22, 1, 0.36, 1);
}
.device {
  position: relative;
  padding: 8px;
  border-radius: 26px;
  background: #15161a;
  border: 1px solid var(--vp-c-divider);
  box-shadow: 0 28px 56px -26px rgba(0, 0, 0, 0.55);
}
@media (hover: hover) {
  .live:hover .stage {
    transform: rotateX(0deg) rotateY(0deg) scale(1.05);
  }
}
/* Tilted while locked (the tap-gate is up); flat once unlocked for easy use. */
.live.activated .stage {
  transform: rotateX(0deg) rotateY(0deg) scale(1.05);
}
.screen {
  display: block;
  width: 100%;
  aspect-ratio: 324 / 560;
  border: 0;
  border-radius: 18px;
  background: #202127;
}
.caption {
  margin: 22px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--vp-c-text-2);
}
/* Narrower, phone-like proportion on stacked (mobile) layouts. */
@media (max-width: 959px) {
  .screen {
    aspect-ratio: 250 / 448;
  }
}

/* Touch-only gate: a tap hands off scrolling/interaction to the iframe. */
.tap-gate {
  position: absolute;
  inset: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 18px;
  background: rgba(10, 12, 14, 0.62);
}
.tap-pill {
  padding: 9px 18px;
  border: none;
  border-radius: 999px;
  background: var(--vp-c-brand-2);
  color: #fff;
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}
@media (hover: hover) {
  .tap-gate {
    display: none;
  }
}
</style>
