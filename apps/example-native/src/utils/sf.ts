import { Platform } from 'react-native'

/** An SF Symbol as artwork: white on a `bg` square. iOS only. */
export const sf = (name: string, bg: string) =>
  Platform.select({ ios: `sf:${name}?bg=${bg}&fg=#fff` })
