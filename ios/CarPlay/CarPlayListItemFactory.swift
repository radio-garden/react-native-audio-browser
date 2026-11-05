import CarPlay
import Foundation
import NitroModules
import os.log

// MARK: - Typed Item Info

/// Stored in a CPListItem's `userInfo`; the playing-indicator sweep finds its rows by it.
struct CarPlayItemInfo {
  /// `id` when non-blank, else `src`.
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

@MainActor
final class CarPlayListItemFactory {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayListItemFactory")

  private let imageLoader: CarPlayImageLoader
  /// The last-applied location; the session sets it, the factory never reads options itself.
  var playingIndicatorLocation: CPListItemPlayingIndicatorLocation
  private let isActiveTrack: (_ identity: String?) -> Bool
  /// Runs work as a task the session owns, so ending the session cancels it.
  private let spawn: (@escaping @MainActor () async -> Void) -> Void
  /// Call `completion` once CarPlay may take the next tap.
  private let onItemSelected: (Track, @escaping () -> Void) -> Void
  /// A tile section's header ("view all") was tapped.
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

  /// Whether this OS has CarPlay's iOS 26 element API: the wrapping tile container and the
  /// text-less image row.
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

  /// One CPListSection per non-empty page section (ADR 0010), within CarPlay's section and
  /// total-item limits; a tile section counts as one item. Style resolves `section ?? page`
  /// (ADR 0011) to a `SectionPresentation`: list rows, a single-line tile row whose overflow
  /// truncates unreported, or a wrapping tile grid (iOS 26+; a list before).
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

  /// - Parameters:
  ///   - style: The resolved style (`track ?? section ?? page`); nil uses the track's own,
  ///     for rows outside a section (Up Next).
  ///   - handler: Replaces the default browse or play on tap; a disabled track still ignores the tap.
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
      item.playingIndicatorLocation = playingIndicatorLocation
      // The phone's CarPlay service draws the indicator, and some iOS and CarPlay Simulator
      // combinations draw none for any app: check a real head unit before suspecting this code.
      item.isPlaying = isActiveTrack(track.identity)
      if item.isPlaying {
        logger.debug("Setting isPlaying=true for: \(track.title) (src: \(src))")
      }
    } else if track.path != nil {
      item.accessoryType = .disclosureIndicator
    }

    // accessoryImage outranks accessoryType in the SDK, and its setter clears accessoryType;
    // 'none' resolves to nil and keeps the derived accessory.
    if let symbol = SectionPresentation.effectiveAccessorySymbol(style ?? track.style),
       let accessory = UIImage(systemName: symbol)
    {
      item.setAccessoryImage(accessory)
    }

    if track.disabled == true {
      item.isEnabled = false
    }

    if track.artwork != nil || track.artworkSource != nil {
      item.setImage(imageLoader.placeholderImage(size: CPListItem.maximumImageSize))
      loadArtwork(for: track, style: style, size: CPListItem.maximumImageSize) { [weak item] image in
        item?.setImage(image)
      }
    }

    let selection = handler ?? { [onItemSelected] _, completion in
      onItemSelected(track, completion)
    }
    // Guards custom handlers too, so a disabled row stays inert if a surface delivers the tap.
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

  /// iOS 26 deprecates `CPListImageRowItem.maximumImageSize` for the element API's.
  private static var rowImageSize: CGSize {
    if #available(iOS 26.0, *) {
      return CPListImageRowItemElement.maximumImageSize
    }
    return CPListImageRowItem.maximumImageSize
  }

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

  /// The single image-row item a tile section renders as; nil when pre-iOS 26 hides every
  /// child as disabled.
  /// - Parameter text: The row's title; nil when the section header carries it.
  private func createImageRowItem(
    for section: Section, text rowText: String?, style: SectionStyle, presentation: SectionPresentation,
  ) -> CPListImageRowItem? {
    let singleLine = presentation == .singleLineRow
    let family = SectionPresentation.tileFamily(for: style)
    // iOS 26 elements can draw a disabled track grayed and inert; the legacy row cannot, so it
    // hides the track rather than show a dead tile.
    let visibleChildren: [Track] = if #available(iOS 26.0, *) {
      section.children
    } else {
      section.children.filter { $0.disabled != true }
    }
    guard !visibleChildren.isEmpty else { return nil }
    // The cap limits only what is drawn, where the platform truncates; the queue is unaffected.
    let tracks = singleLine
      ? Array(visibleChildren.prefix(CPMaximumNumberOfGridImages))
      : visibleChildren
    let placeholder = { [imageLoader] in
      imageLoader.placeholderImage(size: Self.rowImageSize) ?? UIImage()
    }

    let item: CPListImageRowItem
    // Nil on the legacy path, whose element types do not exist before iOS 26.
    var applyImage: (@MainActor (Int, UIImage) -> Void)?
    let resolvedStyles = tracks.map { StyleResolver.trackStyle(track: $0.style, section: style) }
    if #available(iOS 26.0, *) {
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
      switch family {
      case .cardElements:
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
        // Never the title-less gridElements: an artwork-less track keeps its name (ADR 0010).
        item = row(CPListImageRowItem.init(text:imageGridElements:allowsMultipleLines:)) { track, resolved in
          CPListImageRowItemImageGridElement(
            image: placeholder(),
            imageShape: resolved.imageShape == .circular ? .circular : .roundedRectangle,
            title: track.title,
            accessorySymbolName: SectionPresentation.effectiveAccessorySymbol(resolved),
          )
        }
      case .rowElements:
        item = row(CPListImageRowItem.init(text:elements:allowsMultipleLines:)) { track, _ in
          CPListImageRowItemRowElement(
            image: placeholder(), title: track.title, subtitle: track.subtitle,
          )
        }
      }
    } else {
      let placeholders = tracks.map { _ in placeholder() }
      if #available(iOS 17.4, *) {
        item = CPListImageRowItem(text: rowText ?? "", images: placeholders, imageTitles: tracks.map(\.title))
      } else {
        item = CPListImageRowItem(text: rowText ?? "", images: placeholders)
      }
    }

    // `handler` is the header tap; a path-less section is a preview with nothing to open.
    if let path = section.path {
      let title = section.title ?? ""
      item.handler = { [onNavigate] _, completion in
        onNavigate(path, title)
        completion()
      }
    }

    // Keeps a disabled tile inert even if the surface delivers the tap.
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
