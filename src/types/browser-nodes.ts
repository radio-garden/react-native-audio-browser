export interface BrowserBase {
  url?: string
  title: string
  subtitle?: string
  /**
   * Optional icon URL for the item. Will be displayed to the left of the item
   * in lists, or above the item in grid layouts.
   */
  icon?: string
  /**
   * Optional artwork URL for the item.
   */
  artwork?: string
  artist?: string
  album?: string
  description?: string
  genre?: string
  duration?: number
}

export type BrowserItemStyle = 'list' | 'grid'

export interface BrowserSection {
  title: string
  style?: BrowserItemStyle
  children: (Track | BrowserLink)[]
}

export type BrowserItem = Track | BrowserLink

export interface BrowserList extends BrowserBase {
  children: BrowserItem[]
  style?: BrowserItemStyle
  /** When true, indicates this container can be played as a unit (e.g., "Play Album") */
  playable?: boolean
}

export interface Track extends BrowserBase {
  src: string
}

export interface BrowserLink extends BrowserBase {
  /** When true, indicates this link's destination can be played as a unit
   * (e.g., "Play Playlist") */
  playable?: boolean
}
