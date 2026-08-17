<script setup lang="ts">
/**
 * Hero affordance: copy a short brief telling a coding agent where the docs are
 * and how to read them as Markdown, for pasting into CLAUDE.md, a Cursor rule,
 * or straight into a chat.
 *
 * A pointer rather than the docs themselves — it stays correct as pages change,
 * and costs the agent nothing until it follows one of the links.
 */
import { onBeforeUnmount, ref } from 'vue'

// URLs only, and always the canonical origin — never a DOCS_BASE share deploy.
// This text ends up pasted into someone else's repo, unversioned, where a path
// into our package layout would quietly rot the first time we moved it.
const instructions = `When working with react-native-audio-browser, read \
https://audiobrowser.dev/llms.txt first to find the relevant page, then fetch \
that page as Markdown by appending .md to its URL — e.g. \
https://audiobrowser.dev/guide/queue.md.

Every guide in one file: https://audiobrowser.dev/llms-full.txt`

type State = 'idle' | 'copied' | 'failed'

const state = ref<State>('idle')
let resetTimer: ReturnType<typeof setTimeout> | undefined

// The index URL rides alongside the label so the offer is concrete: a reader
// can see where their agent is being sent, and go read it themselves.
const indexUrl = 'https://audiobrowser.dev/llms.txt'

const label = {
  idle: 'Copy to point your AI to the docs',
  copied: 'Copied — paste it into your agent',
  failed: 'Copy failed — select the text below'
}

// navigator.clipboard is undefined outside a secure context (a plain-http
// preview, say), so fall back to the execCommand path before giving up.
async function write(text: string) {
  if (navigator.clipboard) {
    await navigator.clipboard.writeText(text)
    return
  }

  const field = document.createElement('textarea')
  field.value = text
  field.setAttribute('readonly', '')
  field.style.position = 'fixed'
  field.style.opacity = '0'
  document.body.appendChild(field)
  field.select()

  try {
    if (!document.execCommand('copy')) throw new Error('execCommand rejected')
  } finally {
    document.body.removeChild(field)
  }
}

async function copy() {
  clearTimeout(resetTimer)

  try {
    await write(instructions)
    state.value = 'copied'
  } catch {
    state.value = 'failed'
  }

  resetTimer = setTimeout(() => (state.value = 'idle'), 4000)
}

onBeforeUnmount(() => clearTimeout(resetTimer))
</script>

<template>
  <div class="agent-copy">
    <button class="trigger" :class="state" type="button" @click="copy">
      <svg
        class="icon"
        viewBox="0 0 24 24"
        width="15"
        height="15"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <template v-if="state === 'copied'">
          <path d="M20 6 9 17l-5-5" />
        </template>
        <template v-else>
          <rect x="9" y="9" width="11" height="11" rx="2" />
          <path d="M5 15V5a2 2 0 0 1 2-2h10" />
        </template>
      </svg>
      <span>{{ label[state] }}</span>
      <span class="url">{{ indexUrl }}</span>
    </button>

    <!-- Selectable fallback, only once the clipboard has actually refused. -->
    <pre v-if="state === 'failed'" class="fallback">{{ instructions }}</pre>

    <span class="sr-only" role="status" aria-live="polite">
      {{ state === 'idle' ? '' : label[state] }}
    </span>
  </div>
</template>

<style scoped>
.agent-copy {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  margin-top: 22px;
}
.trigger {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 4px 8px;
  text-align: left;
  padding: 6px 12px 6px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--vp-c-text-2);
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
  cursor: pointer;
  transition:
    color 0.2s,
    border-color 0.2s,
    background-color 0.2s;
}
.trigger:hover {
  border-color: var(--vp-c-divider);
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-1);
}
.trigger.copied {
  color: var(--vp-c-brand-1);
}
.trigger:focus-visible {
  outline: 2px solid var(--vp-c-brand-1);
  outline-offset: 2px;
}
.icon {
  flex: none;
}
/* Muted and monospaced so it reads as the destination rather than as part of
   the sentence. Wraps onto its own line before the label has to break. */
.url {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
}
.fallback {
  max-width: 460px;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-2);
  font-size: 12px;
  line-height: 1.6;
  text-align: left;
  white-space: pre-wrap;
  user-select: all;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

/* Line up with the hero actions, which VitePress left-aligns once the layout
   stops being stacked. */
@media (min-width: 960px) {
  .agent-copy {
    align-items: flex-start;
  }
  .trigger {
    justify-content: flex-start;
  }
}
</style>
