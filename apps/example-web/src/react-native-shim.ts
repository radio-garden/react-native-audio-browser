/**
 * Minimal `react-native` surface the library touches on web — just AppState and
 * Image. Aliased in place of react-native (and react-native-web) so the web
 * build stays tiny.
 */

type AppStateStatus = 'active' | 'background' | 'inactive'

export const AppState = {
  currentState: 'active' as AppStateStatus,
  addEventListener(_type: string, _handler: (state: AppStateStatus) => void) {
    return { remove() {} }
  }
}

export const Image = {
  prefetch(uri: string): Promise<boolean> {
    return new Promise((resolve) => {
      const img = new window.Image()
      img.onload = () => resolve(true)
      img.onerror = () => resolve(false)
      img.src = uri
    })
  },
  getSize(uri: string, success?: (width: number, height: number) => void) {
    const img = new window.Image()
    img.onload = () => success?.(img.naturalWidth, img.naturalHeight)
    img.src = uri
  }
}

export const Platform = {
  OS: 'web' as const,
  select: <T,>(specifics: { web?: T; default?: T }) =>
    specifics.web ?? specifics.default
}

export default { AppState, Image, Platform }
