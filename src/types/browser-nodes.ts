export type TrackStyle = 'list' | 'grid'

// export interface BrowserSection {
//   title: string
//   style?: BrowserItemStyle
//   children: Track
// }

export interface Track {
  /**
   * Navigation path. When present, this track is a container (tab, album, playlist, folder)
   * that can be navigated into to view its contents.
   *
   * At least one of `url` or `src` must be defined.
   */
  url?: string
  title: string
  subtitle?: string

  /**
   * Direct audio source identifier. When present, this track can be played directly.
   *
   * This is typically an absolute URL pointing to an audio resource, but it can also be
   * any string (file path, custom identifier, etc.) that will be passed to
   * MediaRequestConfig.resolve to transform it into the actual media request.
   *
   * At least one of `url` or `src` must be defined.
   */
  src?: string

  /**
   * Optional artwork URL for the item.
   */
  artwork?: string
  artist?: string
  album?: string
  description?: string
  genre?: string
  duration?: number
  /**
   * When true without a src, indicates this track can be played as a queue
   * by fetching and playing all its children (e.g., "Play Album", "Play Playlist").
   *
   * @default false - true when src is present
   */
  playable?: boolean
  style?: TrackStyle
}

/**
 * A Track that has been resolved with its children through browsing.
 *
 * This is the return type when browsing a track - it includes the track's
 * metadata along with its immediate children.
 *
 * @example
 * ```ts
 * const resolved = await audioBrowser.navigate('albums/abbey-road');
 * console.log(resolved.url); // "albums/abbey-road"
 * console.log(resolved.title); // "Abbey Road"
 * console.log(resolved.children); // Array of tracks in this album
 * ```
 */
export interface ResolvedTrack extends Track {
  /**
   * URL path for this resolved track. Always present since you navigated to this location.
   */
  url: string

  /**
   * Immediate children of this track. Present for container tracks (albums, playlists, folders).
   * Undefined for leaf tracks (individual songs without children).
   */
  children?: Track[]
}
