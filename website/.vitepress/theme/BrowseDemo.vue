<script setup lang="ts">
/**
 * Hero graphic: a browse list that navigates itself — the library's core idea
 * (tabs → nested lists → playable stations) shown as a live demo. A focus
 * highlight glides to a row, presses it, and the list slides one level deeper
 * (iOS nav-stack style); at a station it drills into a now-playing screen, then
 * pops all the way back and loops.
 *
 * Honors prefers-reduced-motion by rendering a single static list, no motion.
 */
import { onMounted, onUnmounted, ref } from 'vue'

// A leaf with a `duration` is a track (gets a timeline); a leaf without one is a
// live radio station (gets a LIVE indicator). `art` is its now-playing artwork.
type Item = {
  title: string
  children?: Item[]
  duration?: string
  art?: string
}
type Panel = Item | { player: true; station: Item }

const ROW_H = 46

const TREE: Item = {
  title: 'Browse',
  children: [
    {
      title: 'Jazz',
      children: [
        { title: 'Tomato Jobim', duration: '5:22', art: '🍅' },
        { title: 'Kale Jarrett', duration: '6:48', art: '🥬' },
        { title: 'Theonion Monk', duration: '4:36', art: '🧅' }
      ]
    },
    {
      title: 'Ambient',
      children: [
        { title: 'Bonobroccoli', duration: '7:10', art: '🥦' },
        { title: 'Jon Pumpkins', duration: '5:54', art: '🎃' },
        { title: 'Beans of Canada', duration: '6:18', art: '🫘' }
      ]
    },
    {
      title: 'Talk',
      children: [
        { title: 'Couch Potato Hour', art: '🥔' },
        { title: 'Hot Takes', art: '🌶️' },
        { title: 'The Daily Corn', art: '🌽' }
      ]
    },
    {
      title: 'Classical',
      children: [
        { title: 'Carrot Orff', duration: '2:34', art: '🥕' },
        { title: 'Edward Elgarlic', duration: '4:14', art: '🧄' },
        { title: 'Modest Mushroomsky', duration: '9:28', art: '🍄' }
      ]
    }
  ]
}

const path = ref<Panel[]>([TREE])
const viewIndex = ref(0)
const cursor = ref(0)
const pressed = ref(false)

const isPlayer = (p: Panel): p is { player: true; station: Item } =>
  'player' in p
const titleOf = (p: Panel) => (isPlayer(p) ? p.station.title : p.title)
const rowsOf = (p: Panel) => (isPlayer(p) ? [] : (p.children ?? []))
// Header names the current page; the now-playing screen just says "Now Playing".
const headerTitle = (p: Panel) => (isPlayer(p) ? 'Now Playing' : titleOf(p))

let cancelled = false
let timer: ReturnType<typeof setTimeout> | undefined
const sleep = (ms: number) =>
  new Promise<void>((res) => {
    timer = setTimeout(res, ms)
  })
function shuffle<T>(a: T[]): T[] {
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[a[i], a[j]] = [a[j], a[i]]
  }
  return a
}

// Every (genre, station) leaf, drawn from a shuffled bag so the demo plays each
// once before any repeats — no clustering, no back-to-back duplicates.
type Leaf = { genre: Item; station: Item }
const leaves: Leaf[] = (TREE.children ?? []).flatMap((genre) =>
  (genre.children ?? []).map((station) => ({ genre, station }))
)
let bag: Leaf[] = []
let lastLeaf: Leaf | undefined
function nextLeaf(): Leaf {
  if (bag.length === 0) {
    bag = shuffle(leaves.slice())
    // Don't let the last of one shuffle equal the first of the next.
    if (lastLeaf && bag[0] === lastLeaf && bag.length > 1) {
      const j = 1 + Math.floor(Math.random() * (bag.length - 1))
      ;[bag[0], bag[j]] = [bag[j], bag[0]]
    }
  }
  lastLeaf = bag.shift()!
  return lastLeaf
}

async function focusPress(target: number) {
  cursor.value = target
  await sleep(780)
  if (cancelled) return
  pressed.value = true
  await sleep(360)
}

function push(p: Panel) {
  path.value.push(p)
  viewIndex.value = path.value.length - 1
  cursor.value = 0
  pressed.value = false
}

async function run() {
  while (!cancelled) {
    const { genre, station } = nextLeaf()
    // Open the genre.
    await focusPress((TREE.children ?? []).indexOf(genre))
    if (cancelled) return
    push(genre)
    await sleep(850)
    // Open the station's now-playing screen.
    await focusPress((genre.children ?? []).indexOf(station))
    if (cancelled) return
    push({ player: true, station })
    await sleep(2800)
    if (cancelled) return
    // Pop all the way home, then loop.
    viewIndex.value = 0
    await sleep(850)
    if (cancelled) return
    path.value = [TREE]
    viewIndex.value = 0
    cursor.value = 0
    pressed.value = false
    await sleep(1050)
  }
}

onMounted(() => {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
    cursor.value = 0
    return // static list
  }
  run()
})

onUnmounted(() => {
  cancelled = true
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <div class="bd">
    <div class="screen" :style="{ height: ROW_H * 4 + 44 + 'px' }">
      <div class="face">
        <div
          class="track"
          :style="{
            width: path.length * 100 + '%',
            transform: `translateX(${(-viewIndex * 100) / path.length}%)`
          }"
        >
          <section
            v-for="(lvl, i) in path"
            :key="i"
            class="panel"
            :style="{ width: 100 / path.length + '%' }"
          >
            <header class="bar">
              <span v-if="i > 0" class="back">‹</span>
              <span class="ttl">{{ headerTitle(lvl) }}</span>
            </header>

            <div v-if="isPlayer(lvl)" class="player">
              <div class="art">{{ lvl.station.art }}</div>
              <div class="stn">{{ lvl.station.title }}</div>
              <div v-if="lvl.station.duration" class="timeline">
                <span class="tl-track"><span class="tl-fill" /></span>
                <span class="tl-dur">{{ lvl.station.duration }}</span>
              </div>
              <div v-else class="live">Live</div>
            </div>

            <div v-else class="rows">
              <div
                v-if="i === viewIndex"
                class="hl"
                :class="{ pressed }"
                :style="{ transform: `translateY(${cursor * ROW_H}px)` }"
              />
              <div
                v-for="(c, ri) in rowsOf(lvl)"
                :key="ri"
                class="row"
                :class="{ focused: i === viewIndex && ri === cursor }"
              >
                <span class="rt">{{ c.title }}</span>
                <span class="ic">›</span>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bd {
  width: 100%;
  max-width: 300px;
  margin: 0 auto;
  /* Real (one-point) perspective — a vanishing point, so the near edge reads
     larger than the far edge. Softer and more editorial than parallel isometric. */
  perspective: 1100px;
  perspective-origin: 50% 38%;
}
.screen {
  position: relative;
  transform-style: preserve-3d;
  /* A gentle magazine-mockup turn; straightens to face-on on hover. */
  transform: rotateX(3deg) rotateY(-20deg) scale(1.25);
  transition: transform 0.7s cubic-bezier(0.22, 1, 0.36, 1);
}
.screen:hover {
  transform: rotateX(0deg) rotateY(0deg) scale(1.25);
}
.face {
  position: absolute;
  inset: 0;
  overflow: hidden;
  border: 1px solid var(--vp-c-divider);
  border-radius: 14px;
  background: var(--vp-c-bg-soft);
  /* Soft float-shadow for the editorial, sitting-off-the-page feel. */
  box-shadow: 0 30px 60px -24px rgba(0, 0, 0, 0.4);
}
.track {
  display: flex;
  height: 100%;
  transition: transform 0.52s cubic-bezier(0.4, 0, 0.2, 1);
}
.panel {
  flex: 0 0 auto;
  padding: 8px;
}
.bar {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 36px;
  padding: 0 36px;
  font-weight: 600;
  font-size: 13px;
  color: var(--vp-c-text-1);
}
.back {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--vp-c-brand-1);
  font-size: 20px;
  line-height: 1;
}
.rows {
  position: relative;
}
.hl {
  position: absolute;
  inset: 0 0 auto 0;
  height: 46px;
  border-radius: 10px;
  background: var(--vp-c-brand-soft);
  transition:
    transform 0.34s cubic-bezier(0.4, 0, 0.2, 1),
    background 0.2s,
    scale 0.16s;
}
.hl.pressed {
  background: var(--vp-c-brand-2);
  scale: 0.97;
}
.row {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 46px;
  padding: 0 12px;
  font-size: 14px;
  color: var(--vp-c-text-2);
}
.row.focused .rt {
  color: var(--vp-c-text-1);
  font-weight: 600;
}
.ic {
  color: var(--vp-c-text-3);
  font-size: 18px;
}
.row.focused .ic {
  color: var(--vp-c-text-1);
}
.player {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: calc(100% - 36px);
  gap: 10px;
}
.art {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 88px;
  height: 88px;
  border-radius: 16px;
  font-size: 46px;
  line-height: 1;
  background: linear-gradient(
    135deg,
    var(--vp-c-brand-soft),
    var(--vp-c-bg-soft)
  );
  border: 1px solid var(--vp-c-divider);
}
.stn {
  font-size: 15px;
  font-weight: 600;
  color: var(--vp-c-text-1);
}
.live {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--vp-c-text-2);
}
.timeline {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 78%;
}
.tl-track {
  position: relative;
  flex: 1;
  height: 3px;
  border-radius: 2px;
  background: var(--vp-c-divider);
}
.tl-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 22%;
  border-radius: 2px;
  background: var(--vp-c-brand-1);
  /* Creeps slowly; the now-playing screen never lingers long enough to fill. */
  animation: tl 28s linear forwards;
}
.tl-fill::after {
  content: '';
  position: absolute;
  right: -3px;
  top: 50%;
  transform: translateY(-50%);
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--vp-c-brand-1);
}
@keyframes tl {
  from {
    width: 16%;
  }
  to {
    width: 82%;
  }
}
.tl-dur {
  font-size: 10px;
  color: var(--vp-c-text-3);
  font-variant-numeric: tabular-nums;
}
@media (prefers-reduced-motion: reduce) {
  .screen {
    transition: none;
  }
  .track {
    transition: none;
  }
  .tl-fill {
    animation: none;
    width: 45%;
  }
}
</style>
