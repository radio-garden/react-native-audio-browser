import type {
  ResolvedTrack,
  Section,
  SectionStyle,
  Track
} from 'react-native-audio-browser'
import { sf } from './sf'

/**
 * The style lab: every `style` declaration that renders differently on
 * CarPlay, one page per `gridTile` family. Each shelf is titled with the
 * declaration it renders, so what you see on screen names the block to copy.
 *
 * Two kinds of property appear in a block, and they resolve differently:
 *
 * - Container properties (`display`, `gridWrap`, `gridTile`) describe the
 *   shelf. A section's value replaces the page's; a track cannot set them.
 * - Item properties (`imageShape`, `accessorySymbol`, `cardTint`,
 *   `cardImage`, `artworkRendering`) travel with each track and inherit
 *   `track ?? section ?? page`, so a shelf-wide value can be overridden on
 *   one tile.
 *
 * Which tile draws what is `gridTile`'s choice:
 *
 * | gridTile    | subtitle | imageShape | accessorySymbol | card* |
 * | ----------- | -------- | ---------- | --------------- | ----- |
 * | `plain`     | yes      | no         | no              | no    |
 * | `image`     | no       | yes        | yes             | no    |
 * | `card`      | yes      | no         | no              | yes   |
 * | `condensed` | yes      | yes        | yes             | no    |
 *
 * A declaration a tile cannot draw is inert, never an error, which is why
 * the lab leaves those combinations out: a shape on a plain grid shows
 * nothing, and a debug build logs it.
 */

/** Where every lab tile leads: the lab is about how a tile looks. */
const TARGET = '/playlist/independent-sounds'

/**
 * Square and 600 px, so a photograph fills its tile as the symbols do: an
 * image smaller than the tile is drawn smaller, not scaled up.
 */
const photo = (id: number) => `https://picsum.photos/id/${id}/600`

/**
 * The tiles every shelf renders. They are chosen to make differences show:
 *
 * - Photographs sit between flat-color symbols, because a circular crop and
 *   a full-height card look different on a picture than on a flat square.
 * - Every tile has a subtitle, so a family without a subtitle slot is
 *   visible by its absence.
 * - The fifth tile has no artwork: a tile must still show its title over a
 *   placeholder. It is fifth, not last, because a single line draws only
 *   the first seven or so.
 * - There are nine, more than a single line draws, so the truncation
 *   of `gridWrap: false` shows.
 */
const TILES: Track[] = [
  { title: 'Ada', subtitle: 'Host', artwork: sf('person.fill', '#FF0090') },
  { title: 'Basil', subtitle: 'Artist', artwork: photo(14) },
  { title: 'Cleo', subtitle: 'DJ', artwork: sf('headphones', '#8AC926') },
  { title: 'Dario', subtitle: 'Artist', artwork: photo(499) },
  { title: 'No artwork', subtitle: 'Placeholder' },
  { title: 'Esme', subtitle: 'Host', artwork: sf('mic.fill', '#BF5AF2') },
  { title: 'Faro', subtitle: 'Artist', artwork: photo(399) },
  { title: 'Gala', subtitle: 'DJ', artwork: sf('opticaldisc', '#FF375F') },
  { title: 'Hugo', subtitle: 'Artist', artwork: sf('music.note', '#0A84FF') }
].map((track) => ({ ...track, path: TARGET }))

/**
 * The tiles of the stencil shelves: black glyphs on a transparent ground,
 * the kind of artwork `artworkRendering: 'stencil'` is for (station logos,
 * icons). A stencil keeps the glyph's shape and takes its color from
 * CarPlay's light or dark appearance; rendered `'original'`, a black glyph
 * all but disappears on a dark screen.
 *
 * They are fetched bitmaps on purpose. `sf:` artwork is drawn by the library
 * in the colors its own `bg` / `fg` give it and never goes through
 * `artworkRendering`.
 */
const GLYPHS: Track[] = [
  { title: 'userAvatar', file: '9/98/OOjs_UI_icon_userAvatar.svg' },
  { title: 'bell', file: '9/97/OOjs_UI_icon_bell.svg' },
  { title: 'heart', file: '6/6e/OOjs_UI_icon_heart.svg' },
  { title: 'camera', file: '9/91/OOjs_UI_icon_camera.svg' },
  { title: 'globe', file: '8/83/OOjs_UI_icon_globe.svg' },
  { title: 'star', file: '9/99/OOjs_UI_icon_star.svg' },
  { title: 'bookmark', file: '9/98/OOjs_UI_icon_bookmark.svg' },
  { title: 'tag', file: 'd/dd/OOjs_UI_icon_tag-ltr.svg' }
].map(({ title, file }) => ({
  title,
  subtitle: 'Glyph',
  path: TARGET,
  // The PNG thumbnail, by its full path: a URL whose path ends in `.svg` is
  // loaded as an SVG.
  artwork: `https://upload.wikimedia.org/wikipedia/commons/thumb/${file}/960px-${file.slice(5)}.png`
}))

/** What single tiles declare for themselves, by tile number (the first is 1). */
type Overrides = Record<number, Pick<Track, 'style' | 'disabled'>>

/** A shelf's children: `from`, with `overrides` laid over the numbered tiles. */
const tiles = (overrides: Overrides = {}, from: Track[] = TILES): Track[] =>
  from.map((track, index) => ({ ...track, ...overrides[index + 1] }))

/**
 * Track-level overrides inside a shelf that declares `imageShape: 'circular'`
 * and `accessorySymbol: 'star.fill'`:
 *
 * - Tile 2 is rounded. Shape says what kind of thing an item is (people are
 *   round, albums are not), so one grid legitimately mixes both.
 * - Tile 3 has no star. `'none'` is the only way out of an inherited
 *   accessory, since leaving the property off means "inherit".
 * - Tile 4 has a lock instead: the closest declaration wins.
 */
const SHAPE_AND_ACCESSORY: Overrides = {
  2: { style: { imageShape: 'rounded-rectangle' } },
  3: { style: { accessorySymbol: 'none' } },
  4: { style: { accessorySymbol: 'lock.fill' } }
}

/**
 * Track-level overrides inside a shelf that declares a `cardTint`:
 *
 * - Tile 2 fills its card with the image; the tint stops coloring the whole
 *   card and fades in behind the labels. One hero among normal cards.
 * - Tile 3 takes its own tint.
 * - Tile 4 clears the inherited tint with `'none'` and gets CarPlay's
 *   standard card coloring.
 */
const CARD: Overrides = {
  2: { style: { cardImage: 'background' } },
  3: { style: { cardTint: '#9d174d' } },
  4: { style: { cardTint: 'none' } }
}

/**
 * Tile 2 of a stencilled shelf keeps its own colors: full-color artwork
 * among monochrome logos should stay `'original'`.
 */
const ORIGINAL: Overrides = { 2: { style: { artworkRendering: 'original' } } }

/**
 * `disabled` is a fact on the track, not a style, but how it looks depends
 * on the tile: iOS 26 grays the tile and ignores the tap; before iOS 26 the
 * image row cannot gray a tile, so the track is hidden instead.
 */
const DISABLED: Overrides = { 2: { disabled: true }, 3: { disabled: true } }

/**
 * `imageShape` and `accessorySymbol` on the two families that draw them,
 * in both wrap modes. `'image'` with `'circular'` is the grid of large round
 * tiles for people; `'condensed'` draws the same shape small, beside the
 * labels.
 */
function shapedShelves(gridTile: 'image' | 'condensed'): Section[] {
  const shaped: SectionStyle = {
    display: 'grid',
    gridTile,
    imageShape: 'circular',
    accessorySymbol: 'star.fill'
  }
  return [
    {
      title: `${gridTile} · wrap · circular · star.fill`,
      style: shaped,
      children: tiles(SHAPE_AND_ACCESSORY)
    },
    {
      title: `${gridTile} · gridWrap false · circular · star.fill`,
      style: { ...shaped, gridWrap: false },
      children: tiles(SHAPE_AND_ACCESSORY)
    }
  ]
}

/**
 * A family's page: the same four shelves for every family, so they compare
 * like for like, with the family's own shelves (`extra`) after the first two.
 */
function familyPage(
  gridTile: NonNullable<SectionStyle['gridTile']>,
  extra: Section[]
): ResolvedTrack {
  const grid: SectionStyle = { display: 'grid', gridTile }
  return {
    path: `/lab/${gridTile}`,
    title: `gridTile ${gridTile}`,
    sections: [
      // The default: tiles wrap onto as many lines as they need. Before
      // iOS 26 CarPlay has no wrapping tile container, and this is a list.
      { title: `${gridTile} · wrap`, style: grid, children: tiles() },
      // The teaser shelf: one line, truncated. It renders on every iOS, so
      // declaring it is how tiles survive on older cars.
      {
        title: `${gridTile} · gridWrap false`,
        style: { ...grid, gridWrap: false },
        children: tiles()
      },
      ...extra,
      {
        title: `${gridTile} · wrap · stencil`,
        style: { ...grid, artworkRendering: 'stencil' },
        children: tiles(ORIGINAL, GLYPHS)
      },
      {
        title: `${gridTile} · gridWrap false · tiles 2, 3 disabled`,
        style: { ...grid, gridWrap: false },
        children: tiles(DISABLED)
      }
    ]
  }
}

// Large artwork with a title and a subtitle below; no knobs of its own.
const plain = familyPage('plain', [])
// Artwork with a title; the tile that takes a shape and an accessory.
const image = familyPage('image', shapedShelves('image'))
// A small image beside title and subtitle; shape and accessory too.
const condensed = familyPage('condensed', shapedShelves('condensed'))

const tinted: SectionStyle = {
  display: 'grid',
  gridTile: 'card',
  cardTint: '#1e3a8a'
}
// Cards have their own two knobs. `cardTint` colors the card; `cardImage:
// 'background'` fills it with the artwork. A single line of cards is the
// featured "hero row".
const card = familyPage('card', [
  { title: 'card · wrap · cardTint', style: tinted, children: tiles(CARD) },
  {
    title: 'card · gridWrap false · cardTint',
    style: { ...tinted, gridWrap: false },
    children: tiles(CARD)
  },
  {
    title: 'card · wrap · cardImage background',
    style: { ...tinted, cardImage: 'background' },
    children: tiles()
  },
  {
    title: 'card · gridWrap false · cardImage background',
    style: { ...tinted, gridWrap: false, cardImage: 'background' },
    children: tiles()
  }
])

/** What a section's own fields, rather than its style, do to a shelf. */
const header: ResolvedTrack = {
  path: '/lab/header',
  title: 'Section header',
  sections: [
    {
      // A section `path` is the "view all" target: on CarPlay the header
      // becomes tappable and opens it.
      title: 'path · tappable header',
      style: { display: 'grid', gridWrap: false },
      children: tiles(),
      path: TARGET
    },
    // No title: the shelf collapses its header instead of leaving a gap.
    { style: { display: 'grid', gridWrap: false }, children: tiles() },
    // Fewer tiles than a line holds: how a short shelf aligns.
    {
      title: 'three tiles',
      style: { display: 'grid' },
      children: tiles().slice(0, 3)
    }
  ]
}

/**
 * A page block (`style` on the page itself) declares for every section on
 * the page; each shelf below changes one thing about it. Declare the common
 * case once on the page and only the exceptions on sections and tracks.
 */
const inheritance: ResolvedTrack = {
  path: '/lab/inheritance',
  title: 'Inheritance · shape and accessory',
  style: {
    display: 'grid',
    gridTile: 'image',
    gridWrap: false,
    imageShape: 'circular',
    accessorySymbol: 'star.fill'
  },
  sections: [
    // No style of its own: everything comes from the page.
    {
      title: 'page block · image, gridWrap false, circular, star.fill',
      children: tiles()
    },
    // Container properties are replaced per section. `true` and `'plain'`
    // exist as values, not just as defaults, so a section can opt back out
    // of what the page declared.
    {
      title: 'section overrides · gridWrap true',
      style: { gridWrap: true },
      children: tiles()
    },
    // A plain tile has no shape and no accessory, so the page's circle and
    // star do not draw here. They stay declared, and return in the next shelf.
    {
      title: 'section overrides · gridTile plain',
      style: { gridTile: 'plain' },
      children: tiles()
    },
    {
      title: 'section overrides · condensed, rounded-rectangle',
      style: { gridTile: 'condensed', imageShape: 'rounded-rectangle' },
      children: tiles()
    },
    {
      title: 'section overrides · accessorySymbol none',
      style: { accessorySymbol: 'none' },
      children: tiles()
    },
    { title: 'tracks override', children: tiles(SHAPE_AND_ACCESSORY) },
    // In a list the accessory moves to the trailing edge of the row, where
    // it replaces the chevron a browsable row gets; `'none'` brings the
    // chevron back. Rows have no shape.
    {
      title: 'section overrides · display list',
      style: { display: 'list' },
      children: tiles(SHAPE_AND_ACCESSORY).slice(0, 4)
    }
  ]
}

/** The same chain for the card properties and `artworkRendering`. */
const cardInheritance: ResolvedTrack = {
  path: '/lab/inheritance-card',
  title: 'Inheritance · card and artwork',
  style: {
    display: 'grid',
    gridTile: 'card',
    gridWrap: false,
    cardTint: '#1e3a8a',
    cardImage: 'background',
    artworkRendering: 'stencil'
  },
  sections: [
    {
      title: 'page block · card, #1e3a8a, background, stencil',
      children: tiles({}, GLYPHS)
    },
    {
      title: 'section overrides · cardImage normal, #9d174d',
      style: { cardImage: 'normal', cardTint: '#9d174d' },
      children: tiles({}, GLYPHS)
    },
    {
      title: 'section overrides · artworkRendering original',
      style: { artworkRendering: 'original' },
      children: tiles({}, GLYPHS)
    },
    // Each property inherits on its own: a tile that overrides one keeps
    // the page's value for the other two.
    {
      title: 'tracks override',
      children: tiles(
        {
          2: { style: { cardImage: 'normal' } },
          3: { style: { cardTint: 'none' } },
          4: { style: { artworkRendering: 'original' } }
        },
        GLYPHS
      )
    }
  ]
}

const pages = [
  plain,
  image,
  card,
  condensed,
  header,
  inheritance,
  cardInheritance
]

const index: ResolvedTrack = {
  path: '/lab',
  title: 'Style Lab',
  sections: [
    {
      title: 'Pages',
      children: pages.map(({ path, title }) => ({ path, title }))
    },
    // The two item properties a list row draws. Row 2 escapes the lock
    // with `'none'` and gets its chevron back; row 3 takes its own symbol.
    {
      title: 'list · lock.fill',
      style: { accessorySymbol: 'lock.fill' },
      children: tiles({
        2: { style: { accessorySymbol: 'none' } },
        3: { style: { accessorySymbol: 'star.fill' } }
      }).slice(0, 3)
    },
    {
      title: 'list · stencil',
      style: { artworkRendering: 'stencil' },
      children: tiles(ORIGINAL, GLYPHS).slice(0, 3)
    }
  ]
}

export const styleLabRoutes: Record<string, ResolvedTrack> = Object.fromEntries(
  [index, ...pages].map((page) => [page.path, page])
)
