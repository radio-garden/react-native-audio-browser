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

  private let imageLoader: CarPlayImageLoader
  /// The last-applied location; the factory never reads options itself.
  var playingIndicatorLocation: CPListItemPlayingIndicatorLocation
  private let isActiveTrack: (_ identity: String?) -> Bool
  /// Runs an artwork load as a task the session owns, so ending the session cancels it.
  private let spawn: (@escaping @MainActor () async -> Void) -> Void
  /// A row or tile was tapped; call `completion` once CarPlay may take the next tap.
  private let onItemSelected: (Track, @escaping () -> Void) -> Void
  /// A section header's "view all" was tapped.
  private let onNavigate: (_ path: String, _ title: String) -> Void

  init(
    imageLoader: CarPlayImageLoader,
    playingIndicatorLocation: CPListItemPlayingIndicatorLocation,
    isActiveTrack: @escaping (_ identity: String?) -> Bool,
    spawn: @escaping (@escaping @MainActor () async -> Void) -> Void,
    onItemSelected: @escaping (Track, @escaping () -> Void) -> Void,
    onNavigate: @escaping (_ path: String, _ title: String) -> Void,
  ) {
    self.imageLoader = imageLoader
    self.playingIndicatorLocation = playingIndicatorLocation
    self.isActiveTrack = isActiveTrack
    self.spawn = spawn
    self.onItemSelected = onItemSelected
    self.onNavigate = onNavigate
  }

  /// Loads `track`'s artwork and hands it to `apply` unless the session ended meanwhile.
  private func loadArtwork(
    for track: Track, style: TrackStyle?, size: CGSize, apply: @escaping @MainActor (UIImage?) -> Void,
  ) {
    spawn { [imageLoader] in
      let image = await imageLoader.artwork(for: track, style: style, size: size)
      guard !Task.isCancelled else { return }
      apply(image)
    }
  }

  // MARK: - Sections

  /// Whether this OS has CarPlay's iOS 26 element API — the wrapping tile
  /// container and the text-less image row `SectionPresentation` degrades
  /// against.
  private static var supportsElements: Bool {
    if #available(iOS 26.0, *) { return true }
    return false
  }

  private static func listSection(_ items: [CPListTemplateItem], header: String?) -> CPListSection {
    if let header {
      return CPListSection(items: items, header: header, sectionIndexTitle: nil)
    }
    return CPListSection(items: items)
  }

  /// Maps the page's sections 1:1 to CPListSections (ADR 0010), respecting
  /// CarPlay's section and total-item budgets. Style blocks declare the
  /// requested presentation (resolved `section ?? page` here — ADR 0011);
  /// this renders CarPlay's nearest supported form (`SectionPresentation`):
  /// - list (default): a titled/headerless section of list rows.
  /// - single-line grid (`gridWrap: false`): a section holding one
  ///   single-line image-row item — the tiles that fit render, the rest
  ///   truncate (the platform doesn't report the fit).
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
      let presentation = SectionPresentation(for: style, supportsWrappingGrid: Self.supportsElements)
      switch presentation {
      case .list:
        let availableSlots = maxTotalItems - totalItemCount
        let items: [CPListTemplateItem] = section.children.prefix(availableSlots).map {
          createListItem(for: $0, style: StyleResolver.trackStyle(track: $0.style, section: style))
        }
        listSections.append(Self.listSection(items, header: section.title))
        totalItemCount += items.count
      case .singleLineRow, .wrappingGrid:
        // Tile presentations render as one image-row item in its own
        // CPListSection. The title is drawn once: as the section's header
        // (`SectionPresentation.tileSectionHeader`), or as the item's text.
        let header = SectionPresentation.tileSectionHeader(section, supportsElements: Self.supportsElements)
        guard let item = createImageRowItem(
          for: section, text: header == nil ? section.title : nil, style: style, presentation: presentation,
        ) else { continue }
        listSections.append(Self.listSection([item], header: header))
        totalItemCount += 1
      }
    }

    return listSections
  }

  // MARK: - List Item

  /// A list row for `track`: its identity, accessory, playing flag, artwork and tap handler.
  /// - Parameters:
  ///   - style: The track's resolved style block (`track ?? section ?? page`); nil for the
  ///     track's own declaration, when there is no section (Up Next).
  ///   - handler: Replaces the default browse or play on tap.
  func createListItem(
    for track: Track,
    style: TrackStyle? = nil,
    handler: ((CPSelectableListItem, @escaping () -> Void) -> Void)? = nil,
  ) -> CPListItem {
    let item = CPListItem(
      text: track.title,
      detailText: track.subtitle,
    )

    item.setCarPlayItemInfo(CarPlayItemInfo(identity: track.identity))

    if let src = track.src {
      item.accessoryType = .none
      // The phone's CarPlay service draws the indicator, and some iOS and CarPlay Simulator
      // combinations draw none for any app: check a real head unit before suspecting this code.
      item.playingIndicatorLocation = playingIndicatorLocation
      item.isPlaying = isActiveTrack(track.identity)
      if item.isPlaying {
        logger.debug("Setting isPlaying=true for: \(track.title) (src: \(src))")
      }
    } else if track.path != nil {
      item.accessoryType = .disclosureIndicator
    }

    // A resolved accessorySymbol replaces the derived accessory above
    // (accessoryImage outranks accessoryType in the SDK). 'none' — the
    // inheritance escape — keeps the derived behavior instead.
    if let symbol = SectionPresentation.effectiveAccessorySymbol(style ?? track.style),
       let accessory = UIImage(systemName: symbol)
    {
      // `accessoryImage` is readonly on CPListItem; post-init updates go
      // through the setter (which also clears `accessoryType`, per the SDK).
      item.setAccessoryImage(accessory)
    }

    // An unavailable track grays out and goes inert — list rows are the
    // surface that CAN draw the fact, so it shows rather than hides
    // (Track.disabled's rendering ladder).
    if track.disabled == true {
      item.isEnabled = false
    }

    if track.artwork != nil || track.artworkSource != nil {
      item.setImage(imageLoader.placeholderImage(size: CPListItem.maximumImageSize))
      loadArtwork(for: track, style: style, size: CPListItem.maximumImageSize) { [weak item] image in
        item?.setImage(image)
      }
    }

    // The disabled guard wraps custom handlers too (Up Next rows), so an unavailable track
    // stays inert even if a surface delivers the tap.
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

  /// Target artwork size for one tile. Cards, image tiles and plain row
  /// elements read their own class maximums ('background' selects the larger
  /// full-height card target); condensed tiles use the shared element size.
  private static func tileImageSize(
    family: SectionPresentation.TileElementFamily, cardImage: CardImage?,
  ) -> CGSize {
    guard #available(iOS 26.0, *) else { return CPListImageRowItem.maximumImageSize }
    switch family {
    case .cardElements:
      return cardImage == .background
        ? CPListImageRowItemCardElement.maximumFullHeightImageSize
        : CPListImageRowItemCardElement.maximumImageSize
    case .rowElements:
      return CPListImageRowItemRowElement.maximumImageSize
    case .imageGridElements:
      return CPListImageRowItemImageGridElement.maximumImageSize
    case .condensedElements:
      return CPListImageRowItemElement.maximumImageSize
    }
  }

  /// Creates the image-row item rendering a tile-presented section: a single
  /// truncating line (`.singleLineRow`) or a wrapping multi-line grid
  /// (`.wrappingGrid` — constructed on iOS 26+ only, by `SectionPresentation`).
  ///
  /// Returns nil when nothing is drawable — every child hidden by the
  /// disabled ladder (pre-26 only; the element APIs gray instead).
  ///
  /// - Parameter text: the row's own title; nil when the list section's
  ///   header carries it.
  private func createImageRowItem(
    for section: Section, text rowText: String?, style: SectionStyle, presentation: SectionPresentation,
  ) -> CPListImageRowItem? {
    let singleLine = presentation == .singleLineRow
    let family = SectionPresentation.tileFamily(for: style)
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
      imageLoader.placeholderImage(size: Self.rowImageSize) ?? UIImage()
    }

    let item: CPListImageRowItem
    // Sets a loaded image on its tile's element; nil on the legacy gridImages path, which has
    // no elements (their types do not exist before iOS 26).
    var applyImage: (@MainActor (Int, UIImage) -> Void)?
    let resolvedStyles = tracks.map { StyleResolver.trackStyle(track: $0.style, section: style) }
    if #available(iOS 26.0, *) {
      // One element per track, grayed when disabled, built into the row by the family's
      // initializer; each tile's image lands on its element.
      func row<Element: CPListImageRowItemElement>(
        _ build: (String?, [Element], Bool) -> CPListImageRowItem,
        element make: (Track, TrackStyle) -> Element,
      ) -> CPListImageRowItem {
        let elements = zip(tracks, resolvedStyles).map { track, resolved in
          let element = make(track, resolved)
          if track.disabled == true {
            element.isEnabled = false
          }
          return element
        }
        applyImage = { index, image in
          guard index < elements.count else { return }
          elements[index].image = image
        }
        return build(rowText, elements, !singleLine)
      }
      // Pre-26 the family's treatment drops and the layout survives (the legacy branch below).
      switch family {
      case .cardElements:
        // Cards have no shape and no accessory slot; 'background' fills the card full-height
        // and fades the tint in behind the labels.
        item = row(CPListImageRowItem.init(text:cardElements:allowsMultipleLines:)) { track, resolved in
          CPListImageRowItemCardElement(
            image: placeholder(),
            showsImageFullHeight: resolved.cardImage == .background,
            title: track.title,
            subtitle: track.subtitle,
            tintColor: resolved.cardTint.flatMap { UIColor(declaredHex: $0) },
          )
        }
      case .condensedElements:
        item = row(CPListImageRowItem.init(text:condensedElements:allowsMultipleLines:)) { track, resolved in
          CPListImageRowItemCondensedElement(
            image: placeholder(),
            imageShape: resolved.imageShape == .circular ? .circular : .roundedRectangle,
            title: track.title,
            subtitle: track.subtitle,
            accessorySymbolName: SectionPresentation.effectiveAccessorySymbol(resolved),
          )
        }
      case .imageGridElements:
        // imageGridElements, never the title-less gridElements: an
        // artwork-less track must keep its name on screen (ADR 0010).
        item = row(CPListImageRowItem.init(text:imageGridElements:allowsMultipleLines:)) { track, resolved in
          CPListImageRowItemImageGridElement(
            image: placeholder(),
            imageShape: resolved.imageShape == .circular ? .circular : .roundedRectangle,
            title: track.title,
            accessorySymbolName: SectionPresentation.effectiveAccessorySymbol(resolved),
          )
        }
      case .rowElements:
        // Titled, so an artwork-less track keeps its name on screen
        // (ADR 0010); no shape and no accessory slot.
        item = row(CPListImageRowItem.init(text:elements:allowsMultipleLines:)) { track, _ in
          CPListImageRowItemRowElement(
            image: placeholder(), title: track.title, subtitle: track.subtitle,
          )
        }
      }
    } else {
      let placeholders = tracks.map { _ in placeholder() }
      // Use imageTitles variant on iOS 17.4+ to show titles below each thumbnail
      if #available(iOS 17.4, *) {
        item = CPListImageRowItem(text: rowText ?? "", images: placeholders, imageTitles: tracks.map(\.title))
      } else {
        item = CPListImageRowItem(text: rowText ?? "", images: placeholders)
      }
    }

    // Header tap → navigate to section.path ("view all"). A path-less
    // section is a pure preview and gets no handler.
    if let path = section.path {
      let title = section.title ?? ""
      item.handler = { [onNavigate] _, completion in
        onNavigate(path, title)
        completion()
      }
    }

    // A disabled tile is grayed and inert (iOS 26 elements); the guard keeps it inert even if
    // the surface delivers the tap.
    item.listImageRowHandler = { [onItemSelected] _, index, completion in
      guard index < tracks.count, tracks[index].disabled != true else {
        completion()
        return
      }
      onItemSelected(tracks[index], completion)
    }

    for (index, track) in tracks.enumerated() {
      guard track.artwork != nil || track.artworkSource != nil else { continue }

      let resolved = resolvedStyles[index]
      loadArtwork(
        for: track,
        style: resolved,
        size: Self.tileImageSize(family: family, cardImage: resolved.cardImage),
      ) { [weak item, applyImage] image in
        guard let item, let image else { return }
        if let applyImage {
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

    return item
  }
}
