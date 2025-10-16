/**
 * Base properties for all media browser items.
 *
 * Special case: When url is '/', all children are treated as navigation tabs
 * by consuming clients. Limited to maximum 4 children for automotive compatibility.
 */
type MediaNode = {
  url: string;
  title: string;
  subtitle?: string;
  icon?: string;
  artwork?: string;
};

/**
 * A section of grouped media items with a shared title.
 * When transforming to Android Auto, items in this section will be flattened
 * and marked with DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE to create
 * visual groupings in the media browser interface.
 */
type MediaItemSection = {
  title: string;
  style?: "list" | "grid";
  children: (MediaItem | MediaLink)[];
};

/**
 * A browsable container of media items, navigation links, and organized sections.
 * Represents a single page/endpoint in the media browser hierarchy.
 * The style property sets the default presentation for direct children,
 * while individual items and sections can override with their own styles.
 */
type MediaList = MediaNode & {
  children: (MediaItem | MediaLink | MediaItemSection)[];
  style?: "list" | "grid";
  /** When true, indicates this container can be played as a unit (e.g., "Play Album") */
  playable?: boolean;
};

/**
 * A playable media item such as a song, podcast episode, or radio stream.
 * Contains a streaming URL that can be played directly by media players.
 * Not browsable - represents terminal content in the media browser hierarchy.
 */
type MediaItem = MediaNode & {
  src: string;
};

/**
 * A browsable navigation link to another page in the media browser hierarchy.
 * Contains an href URL that leads to another MediaList endpoint.
 * Used for folders, categories, artists, albums, and other organizational
 * structures.
 */
type MediaLink = MediaNode & {
  href: string;
  /** When true, indicates this link's destination can be played as a unit
   * (e.g., "Play Playlist") */
  playable?: boolean;
};
