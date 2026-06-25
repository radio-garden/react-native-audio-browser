import Foundation
import os.log

#if AUDIOBROWSER_ENABLE_CAST

  import GoogleCast

  /// Builds and maintains the receiver's mirrored queue (ADR-0003): the
  /// current-item-first load, the resolve-and-insert-around-current logic, and the
  /// receiver→`Track` rehydration used on cold-relaunch-while-casting.
  ///
  /// Split out of `CastSessionManager` so the session-lifecycle object stays
  /// focused — symmetric with how Android separates the converter / re-sign /
  /// controller. SDK-coupled and gated; the session manager owns it and drives it.
  ///
  /// All its collaborators are injected (coordinator, remote-client accessor, URL
  /// resolvers) so it never reaches into a global itself — the session manager is
  /// the single owner of "how to reach the receiver".
  @MainActor
  final class CastQueueMirror {
    private let logger = Logger(subsystem: "com.audiobrowser", category: "CastQueueMirror")

    /// Reaches into playback core for the queue snapshot + rehydration target.
    /// Weak — the coordinator outlives this via TrackPlayer.
    private weak var coordinator: PlaybackCoordinator?

    /// The active receiver client, injected as an accessor (re-read on each use —
    /// it changes as sessions come and go — so a closure, not a snapshot).
    private let remoteClient: () -> GCKRemoteMediaClient?

    /// Resolves a media/artwork URL for the Cast device (`target: .cast`).
    private let resolveMediaUrl: (_ src: String, _ track: Track) async -> String
    private let resolveArtworkUrl: (_ track: Track) async -> String?

    init(
      coordinator: PlaybackCoordinator,
      remoteClient: @escaping () -> GCKRemoteMediaClient?,
      resolveMediaUrl: @escaping (_ src: String, _ track: Track) async -> String,
      resolveArtworkUrl: @escaping (_ track: Track) async -> String?,
    ) {
      self.coordinator = coordinator
      self.remoteClient = remoteClient
      self.resolveMediaUrl = resolveMediaUrl
      self.resolveArtworkUrl = resolveArtworkUrl
    }

    /// Build and load the mirrored queue onto the receiver, starting at the
    /// coordinator's current index and the given position.
    ///
    /// Current-item-first: the current item is resolved (`target: .cast`) and
    /// loaded FIRST so audio starts promptly, then the rest of the queue is
    /// resolved and inserted around it. A failure to resolve the rest leaves the
    /// current item playing.
    func loadMirroredQueue(startPosition: Double) {
      guard let coordinator, remoteClient() != nil else { return }
      let tracks = coordinator.tracks
      let currentIndex = max(0, coordinator.currentIndex)
      guard !tracks.isEmpty, currentIndex < tracks.count else {
        logger.info("loadMirroredQueue: empty queue — nothing to cast")
        return
      }
      let autoplay = coordinator.playWhenReady

      Task { @MainActor [weak self] in
        guard let self else { return }

        // 1) Resolve + load the CURRENT item first.
        let current = tracks[currentIndex]
        guard let currentItem = await self.makeQueueItem(for: current, autoplay: autoplay) else {
          self.logger.error("loadMirroredQueue: current item has no resolvable src — aborting")
          return
        }
        guard let remoteClient = self.remoteClient() else { return }
        let queueData = GCKMediaQueueDataBuilder(queueType: .generic)
        queueData.items = [currentItem]
        queueData.startIndex = 0
        queueData.startTime = startPosition
        let loadRequestData = GCKMediaLoadRequestDataBuilder()
        loadRequestData.queueData = queueData.build()
        loadRequestData.autoplay = NSNumber(value: autoplay)
        loadRequestData.startTime = startPosition
        remoteClient.loadMedia(with: loadRequestData.build())
        self.logger.info("loadMirroredQueue: loaded current item (index \(currentIndex)), appending \(tracks.count - 1) more")

        // 2) Resolve the rest and insert them around the current item.
        var before: [GCKMediaQueueItem] = []
        var after: [GCKMediaQueueItem] = []
        for (i, track) in tracks.enumerated() where i != currentIndex {
          guard let item = await self.makeQueueItem(for: track, autoplay: false) else { continue }
          if i < currentIndex { before.append(item) } else { after.append(item) }
        }
        guard let remoteClient2 = self.remoteClient() else { return }
        if !after.isEmpty {
          // Append after the current item (invalid sentinel = append at end).
          remoteClient2.queueInsert(after, beforeItemWithID: kGCKMediaQueueInvalidItemID)
        }
        // Insert the earlier items immediately before the receiver's current
        // item — only when the receiver has assigned it a valid id (it should by
        // now, after the awaits above). If it hasn't, skip the prepend rather
        // than insert against an invalid sentinel (which would append them at the
        // end, corrupting order); previous-track casting is best-effort.
        if !before.isEmpty {
          let currentItemID = remoteClient2.mediaStatus?.currentItemID ?? kGCKMediaQueueInvalidItemID
          if currentItemID != kGCKMediaQueueInvalidItemID {
            remoteClient2.queueInsert(before, beforeItemWithID: currentItemID)
          } else {
            self.logger.error("loadMirroredQueue: receiver current item id not ready — skipping prepend of \(before.count) earlier item(s)")
          }
        }
      }
    }

    /// Rebuild the local Queue from the receiver's mirrored items' customData on
    /// resume into an empty app (cold relaunch). Best-effort; on failure leaves
    /// the queue empty and lets the user re-navigate.
    func rehydrateQueueFromReceiver() {
      guard let coordinator, let remoteClient = remoteClient() else { return }
      let count = remoteClient.mediaQueue.itemCount
      guard count > 0 else { return }
      var tracks: [Track] = []
      for i in 0 ..< count {
        if let item = remoteClient.mediaQueue.item(at: i, fetchIfNeeded: true),
           let track = CastMediaItemConverter.track(from: item.mediaInformation) {
          tracks.append(track)
        }
      }
      guard !tracks.isEmpty else { return }
      // Map the receiver's current ITEM ID to an index (not the id itself).
      let currentItemID = remoteClient.mediaStatus?.currentItemID ?? kGCKMediaQueueInvalidItemID
      let mappedIndex = Self.receiverQueueIndex(for: currentItemID, client: remoteClient) ?? 0
      let startIndex = min(max(0, mappedIndex), tracks.count - 1)
      logger.info("rehydrateQueueFromReceiver: rebuilt \(tracks.count) track(s), startIndex=\(startIndex)")
      coordinator.setQueue(tracks, initialIndex: startIndex, playWhenReady: false)
    }

    /// Resolve a track's media + artwork URL for the Cast device and build a
    /// queue item. Returns nil when the track has no `src` (skip it).
    private func makeQueueItem(for track: Track, autoplay: Bool) async -> GCKMediaQueueItem? {
      guard let src = track.src else { return nil }
      let mediaUrl = await resolveMediaUrl(src, track)
      let artworkUrl = await resolveArtworkUrl(track)
      return CastMediaItemConverter.queueItem(
        for: track, mediaUrl: mediaUrl, artworkUrl: artworkUrl, autoplay: autoplay,
      )
    }

    /// Map a receiver queue item id to its index in the receiver's media queue.
    /// Shared with the session manager's status listener.
    static func receiverQueueIndex(for itemID: UInt, client: GCKRemoteMediaClient) -> Int? {
      guard itemID != kGCKMediaQueueInvalidItemID else { return nil }
      let index = client.mediaQueue.indexOfItem(withID: itemID)
      // NSNotFound sentinel → unknown.
      return index == NSNotFound ? nil : Int(index)
    }
  }

#endif
