import CarPlay
import Foundation
import NitroModules
import os.log

// MARK: - Typed Item Info

/// Typed metadata stored on CPListItems, replacing stringly-typed userInfo dictionaries.
struct CarPlayItemInfo {
  let src: String?
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
  private let isActiveTrack: (String) -> Bool
  private let onItemSelected: (Track, @escaping () -> Void) -> Void

  init(
    isActiveTrack: @escaping (String) -> Bool,
    onItemSelected: @escaping (Track, @escaping () -> Void) -> Void
  ) {
    self.isActiveTrack = isActiveTrack
    self.onItemSelected = onItemSelected
  }

  // MARK: - Sections

  func createSections(from resolvedTrack: ResolvedTrack) -> [CPListSection] {
    guard let children = resolvedTrack.children else {
      return []
    }

    let maxSections = CPListTemplate.maximumSectionCount
    let maxTotalItems = CPListTemplate.maximumItemCount

    // Group by groupTitle if present
    var groups: [String?: [Track]] = [:]
    for track in children {
      let groupKey = track.groupTitle
      groups[groupKey, default: []].append(track)
    }

    // Create sections (respecting both section and total item limits)
    var sections: [CPListSection] = []
    var totalItemCount = 0

    // Ungrouped items first
    if let ungrouped = groups[nil], !ungrouped.isEmpty {
      let availableSlots = maxTotalItems - totalItemCount
      let items: [CPListTemplateItem] = ungrouped.prefix(availableSlots).map { track in
        if track.imageRow != nil {
          return createImageRowItem(for: track)
        }
        return createListItem(for: track)
      }
      if !items.isEmpty {
        sections.append(CPListSection(items: items))
        totalItemCount += items.count
      }
    }

    // Then grouped items (respecting section and item limits)
    for (groupTitle, tracks) in groups.sorted(by: { ($0.key ?? "") < ($1.key ?? "") }) {
      guard sections.count < maxSections else { break }
      guard totalItemCount < maxTotalItems else { break }
      guard groupTitle != nil else { continue }

      let availableSlots = maxTotalItems - totalItemCount
      let items: [CPListTemplateItem] = tracks.prefix(availableSlots).map { track in
        if track.imageRow != nil {
          return createImageRowItem(for: track)
        }
        return createListItem(for: track)
      }
      if !items.isEmpty {
        sections.append(CPListSection(items: items, header: groupTitle, sectionIndexTitle: nil))
        totalItemCount += items.count
      }
    }

    return sections
  }

  // MARK: - List Item

  /// Creates a CPListItem for a track with common setup (typed userInfo, artwork, isPlaying).
  /// - Parameters:
  ///   - track: The track to create the item for
  ///   - handler: Optional custom handler. If nil, uses default browse/play handling.
  func createListItem(
    for track: Track,
    handler: ((CPSelectableListItem, @escaping () -> Void) -> Void)? = nil
  ) -> CPListItem {
    let item = CPListItem(
      text: track.title,
      detailText: track.subtitle ?? track.artist
    )

    // Store typed info for updatePlayingIndicators()
    item.setCarPlayItemInfo(CarPlayItemInfo(src: track.src))

    // Set accessory type based on whether track is browsable or playable
    if let src = track.src {
      // Playable track - check if it's currently playing
      item.accessoryType = .none
      item.isPlaying = isActiveTrack(src)
      if item.isPlaying {
        logger.debug("Setting isPlaying=true for: \(track.title) (src: \(src))")
      }
    } else if track.url != nil {
      // Browsable only - show disclosure indicator
      item.accessoryType = .disclosureIndicator
    }

    // Load artwork with size context for proper CDN optimization
    if track.artwork != nil || track.artworkSource != nil {
      // Set empty placeholder to reserve space while loading
      item.setImage(imageLoader?.placeholderImage)
      imageLoader?.loadArtwork(for: track, size: CPListItem.maximumImageSize) { [weak item] image in
        Task { @MainActor in
          item?.setImage(image)
        }
      }
    }

    // Set selection handler
    if let handler {
      item.handler = handler
    } else {
      item.handler = { [onItemSelected] _, completion in
        onItemSelected(track, completion)
      }
    }

    return item
  }

  // MARK: - Image Row Item

  /// Creates a CPListImageRowItem for a track that has an imageRow.
  /// Renders as a horizontal row of tappable artwork thumbnails with a header title.
  func createImageRowItem(for track: Track) -> CPListImageRowItem {
    guard let imageRowItems = track.imageRow else {
      fatalError("createImageRowItem called without imageRow")
    }

    // CPListImageRowItem requires images at init — start with placeholders
    let maxImages = CPMaximumNumberOfGridImages
    let visibleItems = Array(imageRowItems.prefix(maxImages))
    let placeholders = visibleItems.map { _ in imageLoader?.placeholderImage ?? UIImage() }
    let titles = visibleItems.map(\.title)

    // Use imageTitles variant on iOS 17.4+ to show titles below each thumbnail
    let item = if #available(iOS 17.4, *) {
      CPListImageRowItem(text: track.title, images: placeholders, imageTitles: titles)
    } else {
      CPListImageRowItem(text: track.title, images: placeholders)
    }

    // Handler for row header tap → navigate to track.url if present
    item.handler = { [onItemSelected] _, completion in
      onItemSelected(track, completion)
    }

    // Handler for individual image taps
    item.listImageRowHandler = { [onItemSelected] _, index, completion in
      guard index < visibleItems.count else {
        completion()
        return
      }
      let tappedItem = visibleItems[index]
      onItemSelected(tappedItem.toTrack(), completion)
    }

    // Load artwork for each visible image row item asynchronously
    for (index, imageRowItem) in visibleItems.enumerated() {
      guard imageRowItem.artwork != nil || imageRowItem.artworkSource != nil else { continue }

      imageLoader?.loadArtwork(for: imageRowItem.toTrack(), size: CPListImageRowItem.maximumImageSize) { [weak item] image in
        Task { @MainActor in
          guard let item, let image else { return }
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
