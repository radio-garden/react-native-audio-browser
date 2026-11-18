import react from '@vitejs/plugin-react'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

const here = path.dirname(fileURLToPath(import.meta.url))
const libRoot = path.resolve(here, '../..')

// Mirror the docs site's deploy base (see website/.vitepress/config.ts). The demo
// is served at <docs-base>demo/, so its asset URLs must carry that prefix. Defaults
// to '/demo/' for a root deploy; under a subpath set DOCS_BASE at build time.
const docsBase =
  `/${(process.env.DOCS_BASE ?? '/').replace(/^\/+|\/+$/g, '')}/`.replace(
    '//',
    '/'
  )

// Minimal serverless web build of the real react-native-audio-browser library.
// - `react-native` is aliased to a ~30-line shim (the lib only touches AppState
//   + Image on web), so we don't pull in react-native-web.
// - The library is bundled from source so platform-extension resolution picks
//   `native.web.ts` (HTML <audio>/shaka) over the nitro native module.
export default defineConfig({
  base: `${docsBase}demo/`,
  define: {
    '__DEV__': 'false',
    'process.env.NODE_ENV': '"production"'
  },
  resolve: {
    extensions: [
      '.web.tsx',
      '.web.ts',
      '.web.jsx',
      '.web.js',
      '.mjs',
      '.tsx',
      '.ts',
      '.jsx',
      '.js',
      '.json'
    ],
    dedupe: ['react', 'react-dom'],
    alias: [
      {
        find: /^react-native$/,
        replacement: path.resolve(here, 'src/react-native-shim.ts')
      },
      {
        find: 'react-native-audio-browser',
        replacement: path.resolve(libRoot, 'src/index.ts')
      }
    ]
  },
  // Pinned so the docs hero's dev iframe (hardcoded http://localhost:5180/demo/)
  // always finds it. Prod uses same-origin /demo/, so no port there.
  server: { port: 5180, strictPort: true, fs: { allow: [libRoot] } },
  preview: { port: 5180, strictPort: true },
  plugins: [react()],
  build: {
    outDir: path.resolve(libRoot, 'website/public/demo'),
    emptyOutDir: true
  }
})
