import Foundation
import os.log

#if AUDIOBROWSER_ENABLE_CAST

  import GoogleCast

  /// Bounded, reactive re-signing of stale media URLs on the Cast receiver.
  ///
  /// ADR-0003: the full Queue is mirrored onto the receiver and each item's
  /// media URL is signed at load time. Over multi-hour live sessions a signed
  /// URL deep in the queue expires before the receiver reaches it. When the
  /// receiver hits a **stale-URL load error**, we JIT re-resolve *that one item*
  /// (`BrowserManager.resolveMediaUrl(target: .cast)`) and push it back with
  /// `GCKRemoteMediaClient.queueUpdateItems`.
  ///
  /// The attempt count is **capped per item** so a genuinely dead stream can't
  /// drive an infinite reload loop (mirrors the `StuckRecoveryPolicy`
  /// philosophy): once an item exhausts its budget we stop re-signing and let
  /// the error surface.
  ///
  /// Distinguishing "stale URL" from "dead stream" is best-effort and is an
  /// on-device verification risk noted in TODO-CAST.md (open risk #2): we treat
  /// the first few media load failures on an item as recoverable, then give up.
  @MainActor
  final class CastReSign {
    private let logger = Logger(subsystem: "com.audiobrowser", category: "CastReSign")

    /// Re-resolves a track's media URL for the Cast device. Injected so this
    /// type stays decoupled from BrowserManager (set by CastSessionManager to
    /// `browserManager.resolveMediaUrl(_, track:, target: .cast)`).
    var resolveCastUrl: ((_ src: String, _ track: Track) async -> String)?

    /// Pure per-item attempt cap + in-flight dedup (testable, no SDK). This class
    /// keeps only the SDK glue (read identity, resolve, `queueUpdate`).
    private var budget: CastReSignBudget

    init(maxAttemptsPerItem: Int = 3) {
      budget = CastReSignBudget(maxAttemptsPerItem: maxAttemptsPerItem)
    }

    /// Clear all attempt accounting (call on a fresh queueLoad).
    func reset() {
      budget.reset()
    }

    /// React to a remote media load error. Returns true if a re-sign was
    /// dispatched OR one is already in flight for this item (the caller should NOT
    /// surface a terminal error yet); false if the item is out of budget /
    /// unrecoverable (the caller surfaces the real error).
    ///
    /// - Parameters:
    ///   - remoteClient: the active `GCKRemoteMediaClient`.
    ///   - itemID: the receiver queue item that failed (from
    ///     `remoteMediaClient.mediaStatus?.currentItemID` or the error context).
    @discardableResult
    func handleLoadError(remoteClient: GCKRemoteMediaClient, itemID: UInt) -> Bool {
      // A re-sign for this item is already dispatched — wait for it to land. Checked
      // BEFORE the identity read so a transient gap in the receiver's queue (the item
      // momentarily absent mid-resign) can't flip an in-flight item to a false
      // terminal error.
      if budget.isInFlight(itemID) { return true }

      guard let resolveCastUrl else {
        logger.error("CastReSign: no resolver wired — cannot re-sign item \(itemID)")
        return false
      }

      // Read the current item's stashed Track identity off the receiver BEFORE
      // claiming a budget attempt — a missing identity is unrecoverable, but it must
      // not consume one of the few re-sign attempts.
      guard let item = remoteClient.mediaQueue.item(withID: itemID, fetchIfNeeded: false) ?? remoteClient.mediaStatus?.queueItem(withItemID: itemID),
            let track = CastMediaItemConverter.track(from: item.mediaInformation),
            let src = track.src
      else {
        logger.error("CastReSign: could not read Track identity for item \(itemID)")
        return false
      }

      switch budget.shouldAttempt(itemID) {
      case .inFlight:
        // A re-sign is already running for this item — wait for it to land.
        return true
      case .exhausted:
        logger.error("CastReSign: item \(itemID) exhausted re-sign budget — surfacing error")
        return false
      case .attempt:
        break
      }

      logger.info("CastReSign: re-signing item \(itemID)")
      Task { @MainActor in
        // Clear the in-flight mark once this attempt settles (success or not), so
        // a later genuine expiry of the same item can be re-signed again.
        defer { self.budget.markDone(itemID) }
        let freshUrl = await resolveCastUrl(src, track)
        let artworkUrl = track.artwork
        guard let updated = CastMediaItemConverter.queueItem(
          for: track, mediaUrl: freshUrl, artworkUrl: artworkUrl, autoplay: true,
        ) else {
          self.logger.error("CastReSign: re-resolved URL for item \(itemID) is unparseable — giving up")
          return
        }
        // queueUpdateItems replaces the item in place on the receiver without a
        // full reload (which would drop live audio).
        remoteClient.queueUpdate([updated], byReorderingWithIDs: nil, insertBeforeItemWithID: kGCKMediaQueueInvalidItemID)
      }
      return true
    }
  }

#endif
