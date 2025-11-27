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

  // type?: TrackType
  title: string
  subtitle?: string
  artist?: string
  album?: string
  description?: string
  genre?: string
  duration?: number

  /**
   * Display style for this item in Android Auto/AAOS.
   * - 'list': Display as a list row
   * - 'grid': Display as a grid tile
   *
   * On Android: when `artwork` is an `android.resource://` URI
   * (e.g., `android.resource://com.myapp/drawable/ic_folder`), the library
   * automatically uses 'category' styling which adds margins around the icon
   * and enables system tinting for vector drawables.
   *
   * @see https://developer.android.com/training/cars/media#default-content-style
   */
  style?: TrackStyle

  /**
   * Display style for this item's children in Android Auto/AAOS.
   * Only applies to browsable items (containers/folders).
   * - 'list': Display children as list rows
   * - 'grid': Display children as grid tiles
   *
   * Must be set on the item when it appears as a child in a parent's list.
   * Android Auto reads the extras at that point to determine how to display
   * the folder's contents when navigated into.
   */
  childrenStyle?: TrackStyle

  /**
   * Whether this track is favorited. When the `set-rating` capability is enabled,
   * displays a filled/empty heart icon in media controllers (notification, Android Auto).
   */
  favorited?: boolean

  /**
   * Group title for section headers in Android Auto/AAOS.
   * Items with the same groupTitle are displayed under a shared section header.
   * Items must be contiguous to form a single group.
   */
  groupTitle?: string
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
