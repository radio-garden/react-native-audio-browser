import CarPlay
import Foundation
import NitroModules
import os.log

// MARK: - Typed Item Info

/// Typed metadata stored on CPListItems, replacing stringly-typed userInfo dictionaries.
struct CarPlayItemInfo {
  /// The track's identity (`id` when non-blank, else `src`) — the comparison
  /// key for the now-playing indicator repaint sweep.
  let identity: String?
}

extension CPListItem {
  func setCarPlayItemInfo(_ info: CarPlayItemInfo) {
    userInfo = ["carPlayItemInfo": info]
  }

  var carPlayItemInfo: CarPlayItemInfo? {
    (userInfo as? [String: Any])?["carPlayItemInfo"] as? CarPlayItemInfo
  }
}

// MARK: - Factory

/// Creates CPListItems and CPListSections from Track data for CarPlay templates.
@MainActor
final class CarPlayListItemFactory {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayListItemFactory")

  var imageLoader: CarPlayImageLoader?
  /// Pushed by the controller (at creation and when options change), like
  /// `imageLoader` — the factory never reads options itself.
  var playingIndicatorLocation: CPListItemPlayingIndicatorLocation = .leading
  private let isActiveTrack: (_ identity: String?) -> Bool
  private let onItemSelected: (Track, @escaping () -> Void) -> Void

  init(
    isActiveTrack: @escaping (_ identity: String?) -> Bool,
    onItemSelected: @escaping (Track, @escaping () -> Void) -> Void,
  ) {
    self.isActiveTrack = isActiveTrack
    self.onItemSelected = onItemSelected
  }

  // MARK: - Sections

  /// Whether this OS has CarPlay's wrapping tile container (the iOS 26
  /// element API) — the capability `SectionPresentation` degrades against.
  private static var supportsWrappingGrid: Bool {
    if #available(iOS 26.0, *) { return true }
    return false
  }

  /// Maps the page's sections 1:1 to CPListSections (ADR 0010), respecting
  /// CarPlay's section and total-item budgets. Style blocks declare the
  /// requested presentation (resolved `section ?? page` here — ADR 0011);
  /// this renders CarPlay's nearest supported form (`SectionPresentation`):
  /// - list (default): a titled/headerless section of list rows.
  /// - single-line grid (`gridWrap: false`): a headerless section holding
  ///   one single-line image-row item whose text is the section title — the
  ///   tiles that fit render, the rest truncate (the platform doesn't
  ///   report the fit).
  /// - wrapping grid: a wrapping, titled tile grid on iOS 26+; a plain list
  ///   before that.
  func createSections(from resolvedTrack: ResolvedTrack) -> [CPListSection] {
    guard let sections = resolvedTrack.normalizedSections else {
      return []
    }

    let maxSections = CPListTemplate.maximumSectionCount
    let maxTotalItems = CPListTemplate.maximumItemCount

    var listSections: [CPListSection] = []
    var totalItemCount = 0

    for section in sections {
      guard listSections.count < maxSections else { break }
      guard totalItemCount < maxTotalItems else { break }
      // An empty section is a dead end regardless of style (a "popular"
      // section with no entries yet) — skip it rather than render a header
      // with nothing under it.
      guard !section.children.isEmpty else { continue }

      let style = StyleResolver.sectionStyle(section: section.style, page: resolvedTrack.style)
      let presentation = SectionPresentation(for: style, supportsWrappingGrid: Self.supportsWrappingGrid)
      switch presentation {
      case .list:
        let availableSlots = maxTotalItems - totalItemCount
        let items: [CPListTemplateItem] = section.children.prefix(availableSlots).map {
          createListItem(for: $0, style: StyleResolver.trackStyle(track: $0.style, section: style))
        }
        if let title = section.title {
          listSections.append(CPListSection(items: items, header: title, sectionIndexTitle: nil))
        } else {
          listSections.append(CPListSection(items: items))
        }
        totalItemCount += items.count
      case .singleLineRow, .wrappingGrid:
        // Tile presentations render as one image-row item inside a
        // headerless CPListSection, with the section title as the item's
        // text — a headed section would render the title twice.
        guard let item = createImageRowItem(for: section, style: style, presentation: presentation)
        else { continue }
        listSections.append(CPListSection(items: [item]))
        totalItemCount += 1
      }
    }

    return listSections
  }

  // MARK: - List Item

  /// Creates a CPListItem for a track with common setup (typed userInfo, artwork, isPlaying).
  /// - Parameters:
  ///   - track: The track to create the item for
  ///   - style: The track's resolved style block (`track ?? section ?? page`,
  ///     via `StyleResolver`). nil = the track's own declaration — the one
  ///     genuinely section-less caller (the Up Next queue list) has no
  ///     inheritance to resolve.
  ///   - handler: Optional custom handler. If nil, uses default browse/play handling.
  func createListItem(
    for track: Track,
    style: TrackStyle? = nil,
    handler: ((CPSelectableListItem, @escaping () -> Void) -> Void)? = nil,
  ) -> CPListItem {
    let item = CPListItem(
      text: track.title,
      detailText: track.subtitle,
    )

    // Store typed info for updatePlayingIndicators()
    item.setCarPlayItemInfo(CarPlayItemInfo(identity: track.identity))

    // Set accessory type based on whether track is browsable or playable
    if let src = track.src {
      // Playable track - check if it's currently playing
      item.accessoryType = .none
      // Consumer-configurable via `ios.carPlayPlayingIndicatorLocation`
      // (default leading: the indicator draws in the artwork slot). Note the
      // indicator's rendering is owned by the phone's CarPlay service, and on
      // some iOS/CarPlay-Simulator combinations it isn't drawn at all — for
      // ANY third-party app. Verify on a real head unit before assuming a
      // logic bug here.
      item.playingIndicatorLocation = playingIndicatorLocation
      item.isPlaying = isActiveTrack(track.identity)
      if item.isPlaying {
        logger.debug("Setting isPlaying=true for: \(track.title) (src: \(src))")
      }
    } else if track.path != nil {
      // Browsable only - show disclosure indicator
      item.accessoryType = .disclosureIndicator
    }

    // A resolved accessorySymbol replaces the derived accessory above
    // (accessoryImage outranks accessoryType in the SDK). 'none' — the
    // inheritance escape — keeps the derived behavior instead.
    if let symbol = SectionPresentation.effectiveAccessorySymbol(style ?? track.style),
       let accessory = UIImage(systemName: symbol)
    {
      item.accessoryImage = accessory
    }

    // An unavailable track grays out and goes inert — list rows are the
    // surface that CAN draw the fact, so it shows rather than hides
    // (Track.disabled's rendering ladder).
    if track.disabled == true {
      item.isEnabled = false
    }

    // Load artwork with size context for proper CDN optimization
    if track.artwork != nil || track.artworkSource != nil {
      // Set empty placeholder to reserve space while loading
      item.setImage(imageLoader?.placeholderImage(size: CPListItem.maximumImageSize))
      imageLoader?.loadArtwork(for: track, style: style, size: CPListItem.maximumImageSize) { [weak item] image in
        Task { @MainActor in
          item?.setImage(image)
        }
      }
    }

    // Set selection handler. The disabled guard wraps BOTH handler paths —
    // custom handlers (Up Next rows skipping the queue, say) included: belt
    // over the isEnabled braces, so an unavailable track stays inert even if
    // a surface delivers the tap.
    let selection = handler ?? { [onItemSelected] _, completion in
      onItemSelected(track, completion)
    }
    item.handler = { item, completion in
      guard track.disabled != true else {
        completion()
        return
      }
      selection(item, completion)
    }

    return item
  }

  // MARK: - Image Row Item

  /// Target size for images inside an image row. The iOS 26 SDK deprecates
  /// `CPListImageRowItem.maximumImageSize` in favor of the element-based API,
  /// so read whichever the running OS considers canonical.
  private static var rowImageSize: CGSize {
    if #available(iOS 26.0, *) {
      return CPListImageRowItemElement.maximumImageSize
    }
    return CPListImageRowItem.maximumImageSize
  }

  /// Target artwork size for one tile. Cards read their own class maximums
  /// ('background' selects the larger full-height target); every other
  /// family uses the shared element size.
  private static func tileImageSize(gridTile: GridTile?, cardImage: CardImage?) -> CGSize {
    if #available(iOS 26.0, *), gridTile == .card {
      return cardImage == .background
        ? CPListImageRowItemCardElement.maximumFullHeightImageSize
        : CPListImageRowItemCardElement.maximumImageSize
    }
    return rowImageSize
  }

  /// Creates the image-row item rendering a tile-presented section: a single
  /// truncating line (`.singleLineRow`) or a wrapping multi-line grid
  /// (`.wrappingGrid` — constructed on iOS 26+ only, by `SectionPresentation`).
  ///
  /// Returns nil when nothing is drawable — every child hidden by the
  /// disabled ladder (pre-26 only; the element APIs gray instead).
  private func createImageRowItem(
    for section: Section, style: SectionStyle, presentation: SectionPresentation,
  ) -> CPListImageRowItem? {
    let singleLine = presentation == .singleLineRow
    // Disabled ladder: iOS 26 elements can draw unavailability (grayed +
    // inert, below); the legacy image row can't, so there a disabled track
    // hides — never a normal-looking dead tile.
    let visibleChildren: [Track] = if #available(iOS 26.0, *) {
      section.children
    } else {
      section.children.filter { $0.disabled != true }
    }
    guard !visibleChildren.isEmpty else { return nil }
    // A single line shows at most CPMaximumNumberOfGridImages; the wrapping
    // grid takes every child (queue scope is declared, never rendered — the
    // cap here only limits what's drawn, and only where the platform does).
    let tracks = singleLine
      ? Array(visibleChildren.prefix(CPMaximumNumberOfGridImages))
      : visibleChildren
    let placeholder = { [imageLoader] in
      imageLoader?.placeholderImage(size: Self.rowImageSize) ?? UIImage()
    }

    let item: CPListImageRowItem
    // Applies an async-loaded artwork image to a tile on the element-based
    // API (the elements are captured per availability branch — their type
    // doesn't exist before iOS 26). nil = the legacy gridImages path.
    // @MainActor makes the closure Sendable for the artwork completion hop.
    var applyImage: (@MainActor (Int, UIImage) -> Void)?
    // Resolved once per track, shared by element construction and the
    // artwork loads below.
    let resolvedStyles = tracks.map { StyleResolver.trackStyle(track: $0.style, section: style) }
    if #available(iOS 26.0, *) {
      // Builds a family's elements with the shared per-element tail: the
      // disabled fact (isEnabled lives on the element base class) applied
      // once, not per family.
      func makeElements<Element: CPListImageRowItemElement>(
        _ make: (Track, TrackStyle) -> Element,
      ) -> [Element] {
        zip(tracks, resolvedStyles).map { track, resolved in
          let element = make(track, resolved)
          if track.disabled == true {
            element.isEnabled = false
          }
          return element
        }
      }
      // The image setter every element family shares (`image` is on the base
      // class); bounds-checked against the elements the row was built with.
      func imageApplier(_ elements: [some CPListImageRowItemElement]) -> @MainActor (Int, UIImage) -> Void {
        { index, image in
          guard index < elements.count else { return }
          elements[index].image = image
        }
      }
      // The family is style-driven (`SectionPresentation.tileFamily`); both
      // non-plain families take either wrap mode. Pre-26 the treatment drops
      // and the layout survives (the legacy branch below).
      switch SectionPresentation.tileFamily(for: style, singleLine: singleLine) {
      case .cardElements:
        // Cards have no shape and no accessory slot; their knobs are the
        // tint and the image mode ('background' fills the card full-height
        // and turns the tint into the color behind the labels).
        let elements = makeElements { track, resolved in
          CPListImageRowItemCardElement(
            image: placeholder(),
            showsImageFullHeight: resolved.cardImage == .background,
            title: track.title,
            subtitle: track.subtitle,
            tintColor: resolved.cardTint.flatMap { UIColor(declaredHex: $0) },
          )
        }
        item = CPListImageRowItem(
          text: section.title, cardElements: elements, allowsMultipleLines: !singleLine,
        )
        applyImage = imageApplier(elements)
      case .condensedElements:
        let elements = makeElements { track, resolved in
          CPListImageRowItemCondensedElement(
            image: placeholder(),
            imageShape: resolved.imageShape == .circular ? .circular : .roundedRectangle,
            title: track.title,
            subtitle: track.subtitle,
            accessorySymbolName: SectionPresentation.effectiveAccessorySymbol(resolved),
          )
        }
        item = CPListImageRowItem(
          text: section.title, condensedElements: elements, allowsMultipleLines: !singleLine,
        )
        applyImage = imageApplier(elements)
      case .rowElements:
        // The only plain tile with a subtitle slot — the imageGridElements
        // family has no equivalent, by SDK design.
        let elements = makeElements { track, _ in
          CPListImageRowItemRowElement(
            image: placeholder(), title: track.title, subtitle: track.subtitle,
          )
        }
        item = CPListImageRowItem(text: section.title, elements: elements, allowsMultipleLines: false)
        applyImage = imageApplier(elements)
      case .imageGridElements:
        // imageGridElements, never the title-less gridElements: an
        // artwork-less track must keep its name on screen (ADR 0010).
        let elements = makeElements { track, resolved in
          CPListImageRowItemImageGridElement(
            image: placeholder(),
            imageShape: resolved.imageShape == .circular ? .circular : .roundedRectangle,
            title: track.title,
            accessorySymbolName: SectionPresentation.effectiveAccessorySymbol(resolved),
          )
        }
        item = CPListImageRowItem(text: section.title, imageGridElements: elements, allowsMultipleLines: true)
        applyImage = imageApplier(elements)
      }
    } else {
      let placeholders = tracks.map { _ in placeholder() }
      // Use imageTitles variant on iOS 17.4+ to show titles below each thumbnail
      if #available(iOS 17.4, *) {
        item = CPListImageRowItem(text: section.title ?? "", images: placeholders, imageTitles: tracks.map(\.title))
      } else {
        item = CPListImageRowItem(text: section.title ?? "", images: placeholders)
      }
    }

    // Header tap → navigate to section.path ("view all"). A path-less
    // section is a pure preview — its header tap is a no-op rather than a
    // selection that can't resolve.
    item.handler = { [onItemSelected] _, completion in
      guard let path = section.path else {
        completion()
        return
      }
      onItemSelected(Self.navigationTrack(path: path, title: section.title), completion)
    }

    // Per-tile taps select the child track directly. A disabled tile is
    // grayed and inert (26+ elements) — the guard keeps it inert even if
    // the surface delivers the tap anyway.
    item.listImageRowHandler = { [onItemSelected] _, index, completion in
      guard index < tracks.count, tracks[index].disabled != true else {
        completion()
        return
      }
      onItemSelected(tracks[index], completion)
    }

    // Load artwork for each rendered tile asynchronously
    for (index, track) in tracks.enumerated() {
      guard track.artwork != nil || track.artworkSource != nil else { continue }

      let resolved = resolvedStyles[index]
      imageLoader?.loadArtwork(
        for: track,
        style: resolved,
        size: Self.tileImageSize(gridTile: style?.gridTile, cardImage: resolved.cardImage),
      ) { [weak item] image in
        Task { @MainActor in
          guard let item, let image else { return }
          if let applyImage {
            // The element-based API exposes a settable `image`; assigning it
            // updates the element the row observes.
            applyImage(index, image)
          } else {
            var images = item.gridImages
            if index < images.count {
              images[index] = image
              item.update(images)
            }
          }
        }
      }
    }

    return item
  }

  /// A synthetic browsable Track for section-header navigation — the
  /// section's "view all" surface has a path and a title, but no Track.
  /// Style-less by design: CarPlay resolves presentation from the page it
  /// navigates to. (Android's counterpart projects the section's block —
  /// the promise only exists to feed Android Auto's parent-level hint.)
  private static func navigationTrack(path: String, title: String?) -> Track {
    Track(
      id: nil, path: path, src: nil, artwork: nil, artworkSource: nil,
      request: nil, title: title ?? "", subtitle: nil,
      artist: nil, albumPath: nil, album: nil, description: nil, genre: nil,
      duration: nil, style: nil, disabled: nil, favorited: nil, live: nil,
    )
  }
}
