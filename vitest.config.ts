import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    // Tests live in src. Never pick up the compiled copies that `bob build`
    // emits under lib/ — those are CommonJS and fail to `require('vitest')`.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['lib/**', 'node_modules/**']
  }
})
