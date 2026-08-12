import { describe, expect, it, vi } from 'vitest'

vi.mock('../native', () => ({
  nativeBrowser: { configuration: undefined }
}))

import { configureBrowser, getBrowserConfiguration } from './browser'

describe('getBrowserConfiguration', () => {
  it('returns undefined before the browser is configured', () => {
    expect(getBrowserConfiguration()).toBeUndefined()
  })

  it('returns the last configuration in its public shape', () => {
    const config = { tabs: [{ title: 'Home', url: '/' }] }
    configureBrowser(config)
    expect(getBrowserConfiguration()).toBe(config)
  })
})
