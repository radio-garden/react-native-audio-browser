import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  resolve: {
    alias: {
      // `react-native/index.js` is Flow-typed and Rollup cannot parse it, so any
      // test touching a module that imports from 'react-native' fails to load.
      // Jest users get this from @react-native/jest-preset; this is the vitest
      // equivalent. See src/test-utils/reactNativeStub.ts.
      'react-native': fileURLToPath(
        new URL('./src/test-utils/reactNativeStub.ts', import.meta.url)
      )
    }
  },
  test: {
    // Tests live in src. Never pick up the compiled copies that `bob build`
    // emits under lib/ — those are CommonJS and fail to `require('vitest')`.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['lib/**', 'node_modules/**']
  }
})
